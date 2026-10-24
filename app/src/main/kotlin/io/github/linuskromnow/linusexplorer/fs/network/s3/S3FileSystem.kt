package io.github.linuskromnow.linusexplorer.fs.network.s3

import io.github.linuskromnow.linusexplorer.fs.AccessDeniedFsException
import io.github.linuskromnow.linusexplorer.fs.AlreadyExistsFsException
import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.FsException
import io.github.linuskromnow.linusexplorer.fs.NotFoundFsException
import io.github.linuskromnow.linusexplorer.fs.PasswordProtected
import io.github.linuskromnow.linusexplorer.fs.PasswordRequiredFsException
import io.github.linuskromnow.linusexplorer.fs.VfsPaths
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import io.github.linuskromnow.linusexplorer.fs.network.Connection
import io.github.linuskromnow.linusexplorer.fs.network.KnownHosts
import io.github.linuskromnow.linusexplorer.fs.network.Protocol
import io.github.linuskromnow.linusexplorer.fs.network.TofuTrustManager
import io.github.linuskromnow.linusexplorer.fs.network.UntrustedHostKeyException
import io.github.linuskromnow.linusexplorer.fs.network.trustOnFirstUse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * S3-compatible object storage, shown as folders: "/a/b.txt" is the key "a/b.txt"; folders are key
 * prefixes (an empty one is kept alive by a "a/" marker object). Requests are SigV4-signed.
 * [Connection.username] is the access key, the password the secret key, [Connection.share] the bucket.
 */
class S3FileSystem(
    private val connection: Connection,
    private val knownHosts: KnownHosts,
    baseClient: OkHttpClient = OkHttpClient(),
) : VirtualFileSystem, PasswordProtected {
    override val id = connection.fsId
    override val displayName = connection.label.takeIf { connection.name.isNotBlank() } ?: connection.share
    override val rootPath = VfsPaths.ROOT

    private val https = connection.protocol == Protocol.S3
    private val bucket = connection.share
    private val region = connection.region.ifBlank { guessRegion(connection.host) }
    private val trust = TofuTrustManager(knownHosts, connection.host, connection.port)

    /** AWS prefers bucket.host URLs; other providers (and bucket names with dots) get path style. */
    private val virtualHosted = connection.host.endsWith("amazonaws.com") && '.' !in bucket

    @Volatile private var secret: CharArray? = connection.password?.toCharArray()

    private val client: OkHttpClient = baseClient.newBuilder()
        .followRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .apply { if (https) trustOnFirstUse(trust) }
        .build()

    override fun setPassword(password: CharArray) {
        secret = password
    }

    override suspend fun list(path: String): List<FileEntry> = io(path) {
        val dir = VfsPaths.normalize(path)
        val prefix = prefixOf(dir)
        val entries = mutableListOf<FileEntry>()
        var sawMarker = false
        forEachPage(prefix, delimiter = true) { page ->
            page.prefixes.forEach { p -> entries += folder(VfsPaths.child(dir, p.removePrefix(prefix).trimEnd('/'))) }
            page.objects.forEach { o ->
                if (o.key == prefix) sawMarker = true else entries += file(VfsPaths.child(dir, o.key.removePrefix(prefix)), o)
            }
        }
        if (entries.isEmpty() && !sawMarker && dir != VfsPaths.ROOT) throw NotFoundFsException(path)
        entries
    }

    override suspend fun stat(path: String): FileEntry = io(path) { statBlocking(VfsPaths.normalize(path)) }

    override suspend fun exists(path: String): Boolean = try {
        stat(path)
        true
    } catch (e: NotFoundFsException) {
        false
    }

    override suspend fun createDirectory(path: String) = io(path) {
        if (existsBlocking(path)) throw AlreadyExistsFsException(path)
        put(prefixOf(VfsPaths.normalize(path)), ByteArray(0)).close()
    }

    override suspend fun createFile(path: String) = io(path) {
        if (existsBlocking(path)) throw AlreadyExistsFsException(path)
        put(keyOf(path), ByteArray(0)).close()
    }

    override suspend fun delete(path: String) = io(path) {
        val entry = statBlocking(VfsPaths.normalize(path))
        val keys = if (entry.isDirectory) allKeys(prefixOf(entry.path)) else listOf(keyOf(entry.path))
        keys.forEach { key -> call("DELETE", key).close() }
    }

    /** Server-side copy + delete: nothing is downloaded. */
    override suspend fun rename(path: String, newPath: String) = io(path) {
        if (existsBlocking(newPath)) throw AlreadyExistsFsException(newPath)
        val entry = statBlocking(VfsPaths.normalize(path))
        if (entry.isDirectory) {
            val from = prefixOf(entry.path)
            val to = prefixOf(VfsPaths.normalize(newPath))
            val keys = allKeys(from)
            keys.forEach { copy(it, to + it.removePrefix(from)) }
            keys.forEach { call("DELETE", it).close() }
        } else {
            copy(keyOf(path), keyOf(newPath))
            call("DELETE", keyOf(path)).close()
        }
    }

    override suspend fun moveWithin(path: String, newPath: String): Boolean {
        rename(path, newPath)
        return true
    }

    override suspend fun openInput(path: String): InputStream = io(path) {
        val response = call("GET", keyOf(path))
        object : FilterInputStream(response.body.byteStream()) {
            override fun close() = response.close()
        }
    }

    override suspend fun openOutput(path: String): OutputStream = withContext(Dispatchers.IO) { MultipartUpload(keyOf(path), path) }

    // region internals

    private fun statBlocking(path: String): FileEntry {
        if (path == VfsPaths.ROOT) return folder(VfsPaths.ROOT)
        val key = keyOf(path)
        val head = send("HEAD", key)
        head.use { response ->
            if (response.isSuccessful) {
                return FileEntry(
                    name = VfsPaths.name(path),
                    path = path,
                    isDirectory = false,
                    size = response.header("Content-Length")?.toLongOrNull() ?: 0,
                    lastModified = response.headers.getDate("Last-Modified")?.time ?: 0,
                )
            }
            // HEAD has no error body, so a 403 (maybe a bad key) is diagnosed by the listing below.
            if (response.code != 404 && response.code != 403) check(response, path)
        }
        // Not an object: it is a folder if anything lives below it (or its marker exists).
        val page = listPage(key + "/", delimiter = true, maxKeys = 1, token = null)
        if (page.objects.isEmpty() && page.prefixes.isEmpty()) throw NotFoundFsException(path)
        return folder(path)
    }

    private fun existsBlocking(path: String) = try {
        statBlocking(VfsPaths.normalize(path))
        true
    } catch (e: NotFoundFsException) {
        false
    }

    private fun folder(path: String) = FileEntry(VfsPaths.name(path), path, isDirectory = true, size = 0, lastModified = 0)

    private fun file(path: String, o: S3Object) = FileEntry(VfsPaths.name(path), path, isDirectory = false, size = o.size, lastModified = o.lastModified)

    private fun keyOf(path: String) = VfsPaths.normalize(path).removePrefix("/")

    private fun prefixOf(dir: String) = if (dir == VfsPaths.ROOT) "" else keyOf(dir) + "/"

    private fun allKeys(prefix: String): List<String> {
        val keys = mutableListOf<String>()
        forEachPage(prefix, delimiter = false) { page -> page.objects.mapTo(keys) { it.key } }
        return keys
    }

    private fun forEachPage(prefix: String, delimiter: Boolean, onPage: (S3Listing) -> Unit) {
        var token: String? = null
        do {
            val page = listPage(prefix, delimiter, maxKeys = 1000, token = token)
            onPage(page)
            token = page.nextToken
        } while (token != null)
    }

    private fun listPage(prefix: String, delimiter: Boolean, maxKeys: Int, token: String?): S3Listing {
        val query = buildList {
            add("list-type" to "2")
            add("prefix" to prefix)
            add("max-keys" to maxKeys.toString())
            if (delimiter) add("delimiter" to "/")
            token?.let { add("continuation-token" to it) }
        }
        return call("GET", key = null, query = query).use { S3Xml.listing(it.body.byteStream()) }
    }

    private fun put(key: String, data: ByteArray, query: List<Pair<String, String>> = emptyList()): Response =
        call("PUT", key, query, body = data)

    private fun copy(from: String, to: String) {
        val source = "/$bucket/${SigV4.encode(from, keepSlash = true)}"
        call("PUT", to, extraHeaders = mapOf("x-amz-copy-source" to source)).use { response ->
            // S3 can report a failed copy inside a 200 response.
            S3Xml.error(response.body.byteStream())?.let { throw FsException("Copy failed: ${it.message.ifEmpty { it.code }}") }
        }
    }

    /** Sends a signed request and throws the matching [FsException] unless it succeeded. */
    private fun call(
        method: String,
        key: String?,
        query: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Response {
        val response = send(method, key, query, body, extraHeaders)
        if (!response.isSuccessful) response.use { check(it, key ?: "/") }
        return response
    }

    private fun send(
        method: String,
        key: String?,
        query: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Response {
        val host = if (virtualHosted) "$bucket.${connection.host}" else connection.host
        val defaultPort = if (https) 443 else 80
        val hostHeader = if (connection.port == defaultPort) host else "$host:${connection.port}"
        val encodedPath = buildString {
            append('/')
            if (!virtualHosted) append(SigV4.encode(bucket)).append('/')
            key?.let { append(SigV4.encode(it, keepSlash = true)) }
        }
        val accessKey = connection.username
        val secretKey = secret ?: throw PasswordRequiredFsException(id, wrongPassword = false)
        val payload = body ?: if (method == "PUT" || method == "POST") ByteArray(0) else null
        val signed = SigV4(accessKey, String(secretKey), region).sign(
            method = method,
            encodedPath = encodedPath,
            query = query,
            headers = mapOf("host" to hostHeader) + extraHeaders,
            payloadSha256 = payload?.let { SigV4.sha256Hex(it) } ?: SigV4.EMPTY_SHA256,
        )
        val queryString = SigV4.canonicalQuery(query).let { if (it.isEmpty()) "" else "?$it" }
        val url = "${if (https) "https" else "http"}://$hostHeader$encodedPath$queryString".toHttpUrl()
        val request = Request.Builder()
            .url(url)
            .headers((signed + extraHeaders).toHeaders())
            .method(method, payload?.toRequestBody())
            .build()
        trust.rejected = null
        return client.newCall(request).execute()
    }

    private fun check(response: Response, path: String) {
        if (response.isSuccessful) return
        val error = if (response.request.method != "HEAD") S3Xml.error(response.body.byteStream()) else null
        throw when {
            error?.code == "SignatureDoesNotMatch" || error?.code == "InvalidAccessKeyId" ->
                PasswordRequiredFsException(id, wrongPassword = true).also { secret = null }
            error?.code == "AuthorizationHeaderMalformed" || error?.code == "PermanentRedirect" ->
                FsException("Wrong region for bucket “$bucket”${error.region?.let { "; it is in $it" }.orEmpty()}")
            error?.code == "NoSuchBucket" -> FsException("The bucket “$bucket” does not exist")
            error?.code == "RequestTimeTooSkewed" -> FsException("The phone’s clock is off too far for ${connection.host}")
            response.code == 403 -> AccessDeniedFsException(path)
            response.code == 404 -> NotFoundFsException(path)
            response.code in 300..399 -> FsException("${connection.host} redirects to ${response.header("Location")}; check the endpoint and region")
            else -> FsException(error?.message?.ifEmpty { null } ?: "${connection.host}: HTTP ${response.code}")
        }
    }

    private suspend fun <T> io(path: String, block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: FsException) {
            throw e
        } catch (e: IOException) {
            trust.rejected?.let { (cert, changed) -> throw UntrustedHostKeyException(id, connection.host, connection.port, cert, changed) }
            throw FsException("Cannot reach ${connection.host}: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /**
     * S3 needs the size of each request up front, so data is collected in [PART_SIZE] parts: small
     * files become one PUT, bigger ones a multipart upload that is aborted if anything fails.
     */
    private inner class MultipartUpload(private val key: String, private val path: String) : OutputStream() {
        private val buffer = ByteArrayOutputStream()
        private var uploadId: String? = null
        private val etags = mutableListOf<String>()
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) = guard {
            var offset = off
            var remaining = len
            while (remaining > 0) {
                val count = minOf(remaining, PART_SIZE - buffer.size())
                buffer.write(b, offset, count)
                offset += count
                remaining -= count
                if (buffer.size() == PART_SIZE) uploadPart()
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            guard {
                if (uploadId == null) {
                    put(key, buffer.toByteArray()).close()
                } else {
                    if (buffer.size() > 0) uploadPart()
                    val xml = buildString {
                        append("<CompleteMultipartUpload>")
                        etags.forEachIndexed { i, etag -> append("<Part><PartNumber>${i + 1}</PartNumber><ETag>$etag</ETag></Part>") }
                        append("</CompleteMultipartUpload>")
                    }
                    call("POST", key, listOf("uploadId" to uploadId!!), xml.toByteArray()).use { response ->
                        S3Xml.error(response.body.byteStream())?.let { throw FsException("Upload failed: ${it.message.ifEmpty { it.code }}") }
                    }
                }
            }
        }

        private fun uploadPart() {
            val id = uploadId ?: call("POST", key, listOf("uploads" to "")).use { S3Xml.uploadId(it.body.byteStream()) }
                .also { uploadId = it }
            val part = etags.size + 1
            put(key, buffer.toByteArray(), listOf("partNumber" to part.toString(), "uploadId" to id)).use { response ->
                etags += response.header("ETag") ?: throw FsException("Upload of part $part failed")
            }
            buffer.reset()
        }

        private fun guard(block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                uploadId?.let { id -> runCatching { send("DELETE", key, listOf("uploadId" to id)).close() } }
                uploadId = null
                throw if (e is IOException && e !is FsException) FsException("Upload of ${VfsPaths.name(path)} failed: ${e.message}", e) else e
            }
        }
    }

    // endregion

    companion object {
        private const val PART_SIZE = 8 * 1024 * 1024

        /** "s3.eu-central-1.amazonaws.com" -> "eu-central-1"; Cloudflare R2 wants "auto"; otherwise us-east-1. */
        fun guessRegion(host: String): String {
            if (host.endsWith(".r2.cloudflarestorage.com")) return "auto"
            return Regex("""(?:^|\.)s3[.-]([a-z]{2}(?:-[a-z]+)+-\d+)\.""").find(host)?.groupValues?.get(1) ?: "us-east-1"
        }
    }
}

package io.github.linuskromnow.linusexplorer.fs.network.webdav

import io.github.linuskromnow.linusexplorer.fs.AccessDeniedFsException
import io.github.linuskromnow.linusexplorer.fs.AlreadyExistsFsException
import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.FsException
import io.github.linuskromnow.linusexplorer.fs.NotFoundFsException
import io.github.linuskromnow.linusexplorer.fs.PasswordProtected
import io.github.linuskromnow.linusexplorer.fs.PasswordRequiredFsException
import io.github.linuskromnow.linusexplorer.fs.RangeReadable
import io.github.linuskromnow.linusexplorer.fs.RangeUnsupportedFsException
import io.github.linuskromnow.linusexplorer.fs.VfsPaths
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import io.github.linuskromnow.linusexplorer.fs.network.Connection
import io.github.linuskromnow.linusexplorer.fs.network.KnownHosts
import io.github.linuskromnow.linusexplorer.fs.network.Protocol
import io.github.linuskromnow.linusexplorer.fs.network.TofuTrustManager
import io.github.linuskromnow.linusexplorer.fs.network.trustOnFirstUse
import io.github.linuskromnow.linusexplorer.fs.network.UntrustedHostKeyException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.Pipe
import okio.buffer
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/**
 * WebDAV (Nextcloud, ownCloud, NAS, hosters). [Connection.share] is the server path that becomes
 * "/" here, e.g. "/remote.php/dav/files/USER". HTTPS uses the system CAs; self-signed certificates
 * can be trusted once, like SSH host keys.
 */
class WebDavFileSystem(
    private val connection: Connection,
    private val knownHosts: KnownHosts,
    baseClient: OkHttpClient = OkHttpClient(),
) : VirtualFileSystem, PasswordProtected, RangeReadable {
    override val id = connection.fsId
    override val displayName = connection.label
    override val rootPath = VfsPaths.ROOT

    private val https = connection.protocol == Protocol.WEBDAV
    private val basePath = connection.share.split('/').filter { it.isNotEmpty() }
    private val trust = TofuTrustManager(knownHosts, connection.host, connection.port)

    @Volatile private var password: CharArray? = connection.password?.toCharArray()

    private val client: OkHttpClient = baseClient.newBuilder()
        // Redirected DELETE/MOVE requests would silently turn into GETs; report them instead.
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .apply {
            if (https) trustOnFirstUse(trust)
        }
        .addInterceptor { chain ->
            val request = chain.request()
            if (connection.username.isBlank()) return@addInterceptor chain.proceed(request)
            val secret = password ?: throw PasswordRequiredFsException(id, wrongPassword = false)
            chain.proceed(request.newBuilder().header("Authorization", Credentials.basic(connection.username, String(secret), Charsets.UTF_8)).build())
        }
        .build()

    override fun setPassword(password: CharArray) {
        this.password = password
    }

    override suspend fun list(path: String): List<FileEntry> = io(path) {
        val dir = VfsPaths.normalize(path)
        propfind(url(dir, directory = true), depth = 1).mapNotNull { (resourcePath, resource) ->
            resourcePath.takeIf { it != dir }?.let { entry(it, resource) }
        }
    }

    override suspend fun stat(path: String): FileEntry = io(path) {
        val p = VfsPaths.normalize(path)
        val (resourcePath, resource) = propfind(url(p), depth = 0).firstOrNull() ?: throw NotFoundFsException(path)
        entry(if (p == VfsPaths.ROOT) VfsPaths.ROOT else resourcePath, resource)
    }

    override suspend fun exists(path: String): Boolean = try {
        stat(path)
        true
    } catch (e: NotFoundFsException) {
        false
    }

    override suspend fun createDirectory(path: String) = io(path) {
        execute(Request.Builder().url(url(path, directory = true)).method("MKCOL", null).build(), path) { code ->
            if (code == 405) AlreadyExistsFsException(path) else null
        }.close()
    }

    override suspend fun createFile(path: String) = io(path) {
        val request = Request.Builder().url(url(path)).put(ByteArray(0).toRequestBody()).header("If-None-Match", "*").build()
        execute(request, path) { code -> if (code == 412) AlreadyExistsFsException(path) else null }.close()
    }

    override suspend fun delete(path: String) {
        val directory = stat(path).isDirectory
        io(path) { execute(Request.Builder().url(url(path, directory)).delete().build(), path).close() }
    }

    override suspend fun rename(path: String, newPath: String) {
        val directory = stat(path).isDirectory
        io(path) {
            val request = Request.Builder()
                .url(url(path, directory))
                .method("MOVE", null)
                .header("Destination", url(newPath, directory).toString())
                .header("Overwrite", "F")
                .build()
            execute(request, path) { code -> if (code == 412) AlreadyExistsFsException(newPath) else null }.close()
        }
    }

    override suspend fun moveWithin(path: String, newPath: String): Boolean {
        rename(path, newPath)
        return true
    }

    override suspend fun openInput(path: String): InputStream = io(path) {
        val response = execute(Request.Builder().url(url(path)).get().build(), path)
        object : FilterInputStream(response.body.byteStream()) {
            override fun close() = response.close()
        }
    }

    override suspend fun readRange(path: String, offset: Long, length: Int): ByteArray = io(path) {
        val request = Request.Builder().url(url(path)).get().header("Range", "bytes=$offset-${offset + length - 1}").build()
        execute(request, path).use { response ->
            if (response.code != 206) throw RangeUnsupportedFsException() // a 200 would be the whole file
            response.body.bytes()
        }
    }

    /** Streams into a PUT request; the upload finishes (or fails) when the stream is closed. */
    override suspend fun openOutput(path: String): OutputStream = withContext(Dispatchers.IO) {
        val pipe = Pipe(PIPE_BUFFER)
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                sink.writeAll(pipe.source)
            }
        }
        val result = CompletableFuture<Response>()
        val call = client.newCall(Request.Builder().url(url(path)).put(body).build())
        trust.rejected = null
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                pipe.cancel() // unblocks a writer waiting for a request that will never read
                result.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: Response) {
                pipe.cancel()
                result.complete(response)
            }
        })
        val sink = pipe.sink.buffer()
        object : OutputStream() {
            private var closed = false

            override fun write(b: Int) = guard { sink.writeByte(b) }

            override fun write(b: ByteArray, off: Int, len: Int) = guard { sink.write(b, off, len) }

            override fun close() {
                if (closed) return
                closed = true
                runCatching { sink.close() }
                val response = try {
                    result.get(2, TimeUnit.MINUTES)
                } catch (e: ExecutionException) {
                    throw mapIo(e.cause as? IOException ?: IOException(e.cause), path)
                }
                response.use { check(it, path) }
            }

            private fun guard(write: () -> Unit) {
                try {
                    write()
                } catch (e: IOException) {
                    // The request already ended; report why instead of a bare "pipe canceled".
                    val finished = result.getNow(null)
                    if (finished != null) finished.use { check(it, path) }
                    throw mapIo(e, path)
                }
            }
        }
    }

    // region internals

    private suspend fun <T> io(path: String, block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: FsException) {
            throw e
        } catch (e: IOException) {
            throw mapIo(e, path)
        }
    }

    private fun mapIo(e: IOException, path: String): IOException {
        if (e is FsException) return e
        trust.rejected?.let { (cert, changed) -> return UntrustedHostKeyException(id, connection.host, connection.port, cert, changed) }
        if (e is SSLHandshakeException) return FsException("Secure connection to ${connection.host} failed: ${e.message}", e)
        return FsException("Cannot reach ${connection.host}: ${e.message ?: e.javaClass.simpleName}", e)
    }

    private fun execute(request: Request, path: String, special: (Int) -> FsException? = { null }): Response {
        trust.rejected = null
        val response = client.newCall(request).execute()
        if (response.isSuccessful) return response
        response.use {
            special(it.code)?.let { error -> throw error }
            check(it, path)
        }
        return response
    }

    /** Throws the matching [FsException] for an unsuccessful response. */
    private fun check(response: Response, path: String) {
        if (response.isSuccessful) return
        throw when (response.code) {
            401 -> PasswordRequiredFsException(id, wrongPassword = password != null).also { password = null }
            403 -> AccessDeniedFsException(path)
            404, 409 -> NotFoundFsException(path)
            507 -> FsException("Not enough space on ${connection.host}")
            in 300..399 -> FsException("${connection.host} redirects to ${response.header("Location")}; check the server address and path")
            else -> FsException("${connection.host}: HTTP ${response.code} ${response.message}".trim())
        }
    }

    private fun propfind(url: HttpUrl, depth: Int): List<Pair<String, DavResource>> {
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", depth.toString())
            .build()
        return execute(request, url.encodedPath).use { response ->
            if (response.code != 207) throw FsException("${connection.host} is not a WebDAV server (HTTP ${response.code})")
            PropfindParser.parse(response.body.byteStream()).mapNotNull { resource ->
                vfsPath(url, resource.href)?.let { it to resource }
            }
        }
    }

    private fun entry(path: String, resource: DavResource) = FileEntry(
        name = VfsPaths.name(path),
        path = path,
        isDirectory = resource.isCollection,
        size = if (resource.isCollection) 0 else resource.size,
        lastModified = resource.lastModified,
    )

    /** Builds the server URL for a VFS path, below [basePath]. Directories get a trailing slash. */
    private fun url(path: String, directory: Boolean = false): HttpUrl {
        val builder = HttpUrl.Builder()
            .scheme(if (https) "https" else "http")
            .host(connection.host)
            .port(connection.port)
        basePath.forEach { builder.addPathSegment(it) }
        VfsPaths.normalize(path).split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        if (directory) builder.addPathSegment("")
        return builder.build()
    }

    /** Turns a (possibly relative, percent-encoded) href back into a VFS path, or null if it lies outside [basePath]. */
    private fun vfsPath(requestUrl: HttpUrl, href: String): String? {
        val segments = requestUrl.resolve(href)?.pathSegments?.filter { it.isNotEmpty() } ?: return null
        if (segments.size < basePath.size || segments.subList(0, basePath.size) != basePath) return null
        return VfsPaths.normalize("/" + segments.drop(basePath.size).joinToString("/"))
    }

    private companion object {
        const val PIPE_BUFFER = 256L * 1024
    }

    // endregion
}

package io.github.linuskromnow.linusexplorer.fs.network.onedrive

import io.github.linuskromnow.linusexplorer.fs.AccessDeniedFsException
import io.github.linuskromnow.linusexplorer.fs.AlreadyExistsFsException
import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.FsException
import io.github.linuskromnow.linusexplorer.fs.NotFoundFsException
import io.github.linuskromnow.linusexplorer.fs.RangeReadable
import io.github.linuskromnow.linusexplorer.fs.RangeUnsupportedFsException
import io.github.linuskromnow.linusexplorer.fs.VfsPaths
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import io.github.linuskromnow.linusexplorer.fs.network.Connection
import io.github.linuskromnow.linusexplorer.fs.network.cloud.OAuthSession
import io.github.linuskromnow.linusexplorer.fs.network.cloud.SignedOutFsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * OneDrive (personal and work/school) through Microsoft Graph, addressing items by path
 * (`/me/drive/root:/a/b`). [Connection.password] holds the refresh token, which Microsoft replaces
 * on every refresh; [onRefreshTokenChanged] must store the new one. [Connection.share] is the
 * client ID it was issued for (see [OneDrive]).
 *
 * Uploads need the total size up front, so files over [SIMPLE_UPLOAD_LIMIT] are spooled to
 * [spoolDir] first and then sent in [CHUNK]-sized pieces.
 */
class OneDriveFileSystem(
    private val connection: Connection,
    private val spoolDir: File,
    baseClient: OkHttpClient = OkHttpClient(),
    private val graphBase: HttpUrl = GRAPH_BASE,
    tokenUrl: HttpUrl = OneDrive.ENDPOINTS.tokenUrl,
    onRefreshTokenChanged: (String) -> Unit = {},
) : VirtualFileSystem, RangeReadable {
    override val id = connection.fsId
    override val displayName = connection.label
    override val rootPath = VfsPaths.ROOT
    override val serverTrash = "OneDrive"

    private val client = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** For downloads: their redirect is followed by hand, so the token never goes to the target. */
    private val noRedirects = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    private val session = OAuthSession(client, OneDrive.endpoints(tokenUrl), connection.share, connection.password, onRefreshTokenChanged)

    override suspend fun list(path: String): List<FileEntry> = io {
        val dir = VfsPaths.normalize(path)
        val entries = ArrayList<FileEntry>()
        var url: HttpUrl? = item(dir, "children").newBuilder()
            .addQueryParameter("\$top", "1000")
            .addQueryParameter("\$select", SELECT)
            .build()
        while (url != null) {
            val page = json(execute(dir) { Request.Builder().url(url!!).get() })
            val items = page.getJSONArray("value")
            for (i in 0 until items.length()) entries += entry(dir, items.getJSONObject(i))
            url = page.optString("@odata.nextLink").ifEmpty { null }?.toHttpUrl()
        }
        entries
    }

    override suspend fun stat(path: String): FileEntry = io {
        val p = VfsPaths.normalize(path)
        val metadata = json(execute(p) { Request.Builder().url(item(p).newBuilder().addQueryParameter("\$select", SELECT).build()).get() })
        if (p == VfsPaths.ROOT) {
            FileEntry(VfsPaths.name(p), p, isDirectory = true, size = 0, lastModified = 0)
        } else {
            entry(VfsPaths.parent(p) ?: VfsPaths.ROOT, metadata)
        }
    }

    override suspend fun exists(path: String): Boolean = try {
        stat(path)
        true
    } catch (e: NotFoundFsException) {
        false
    }

    override suspend fun createDirectory(path: String) = io {
        val p = VfsPaths.normalize(path)
        val parent = VfsPaths.parent(p) ?: throw AlreadyExistsFsException(p)
        val body = JSONObject().put("name", VfsPaths.name(p)).put("folder", JSONObject()).put(CONFLICT, "fail")
        execute(p) { Request.Builder().url(item(parent, "children")).post(body.toString().toRequestBody(JSON)) }.close()
    }

    override suspend fun createFile(path: String) = io {
        val url = item(path, "content").newBuilder().addEncodedQueryParameter(CONFLICT, "fail").build()
        execute(path) { Request.Builder().url(url).put(ByteArray(0).toRequestBody(OCTET_STREAM)) }.close()
    }

    /** OneDrive moves deleted items to its recycle bin, so this can be undone on onedrive.com. */
    override suspend fun delete(path: String) = io {
        execute(path) { Request.Builder().url(item(path)).delete() }.close()
    }

    override suspend fun rename(path: String, newPath: String) = io {
        val parent = VfsPaths.parent(VfsPaths.normalize(path))
        val newParent = VfsPaths.parent(VfsPaths.normalize(newPath)) ?: throw AlreadyExistsFsException(newPath)
        val body = JSONObject().put("name", VfsPaths.name(newPath))
        if (newParent != parent) {
            val parentId = json(execute(newParent) { Request.Builder().url(item(newParent).newBuilder().addQueryParameter("\$select", "id").build()).get() })
                .getString("id")
            body.put("parentReference", JSONObject().put("id", parentId))
        }
        val url = item(path).newBuilder().addEncodedQueryParameter(CONFLICT, "fail").build()
        execute(path, conflictPath = newPath) { Request.Builder().url(url).patch(body.toString().toRequestBody(JSON)) }.close()
    }

    override suspend fun moveWithin(path: String, newPath: String): Boolean {
        rename(path, newPath)
        return true
    }

    override suspend fun openInput(path: String): InputStream = io {
        val response = download(path, range = null)
        object : FilterInputStream(response.body.byteStream()) {
            override fun close() = response.close()
        }
    }

    override suspend fun readRange(path: String, offset: Long, length: Int): ByteArray = io {
        val range = "bytes=$offset-${offset + length - 1}"
        download(path, range).use { response ->
            if (response.code != 206) throw RangeUnsupportedFsException() // a 200 would be the whole file
            response.body.bytes()
        }
    }

    /** Graph answers with a redirect to a short-lived, pre-authorized download URL. */
    private fun download(path: String, range: String?): Response {
        fun Request.Builder.withRange() = apply { if (range != null) header("Range", range) }
        val first = execute(path, http = noRedirects) { Request.Builder().url(item(path, "content")).get().withRange() }
        if (!first.isRedirect) return first
        val target = first.use { it.header("Location") }?.let { first.request.url.resolve(it) }
            ?: throw FsException("OneDrive sent no download address")
        return execute(path, authorized = false) { Request.Builder().url(target).get().withRange() }
    }

    override suspend fun openOutput(path: String): OutputStream = withContext(Dispatchers.IO) { UploadStream(path) }

    /** Keeps small files in memory and spools bigger ones to disk; uploads on [close]. */
    private inner class UploadStream(private val path: String) : OutputStream() {
        private var memory: ByteArrayOutputStream? = ByteArrayOutputStream()
        private var spool: File? = null
        private var spoolOut: OutputStream? = null
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            val buffer = memory
            if (buffer != null && buffer.size() + len <= SIMPLE_UPLOAD_LIMIT) {
                buffer.write(b, off, len)
                return
            }
            val out = spoolOut ?: run {
                spoolDir.mkdirs()
                val file = File.createTempFile("upload", ".part", spoolDir).also { spool = it }
                FileOutputStream(file).buffered().also { stream ->
                    buffer?.writeTo(stream)
                    memory = null
                    spoolOut = stream
                }
            }
            out.write(b, off, len)
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                val buffer = memory
                if (buffer != null) {
                    blocking {
                        val bytes = buffer.toByteArray()
                        execute(path) { Request.Builder().url(item(path, "content")).put(bytes.toRequestBody(OCTET_STREAM)) }.close()
                    }
                } else {
                    spoolOut!!.close()
                    blocking { uploadInChunks(path, spool!!) }
                }
            } finally {
                runCatching { spoolOut?.close() }
                spool?.delete()
            }
        }
    }

    private fun uploadInChunks(path: String, file: File) {
        val args = JSONObject().put("item", JSONObject().put(CONFLICT, "replace"))
        val uploadUrl = json(execute(path) { Request.Builder().url(item(path, "createUploadSession")).post(args.toString().toRequestBody(JSON)) })
            .getString("uploadUrl").toHttpUrl()
        val total = file.length()
        val chunk = ByteArray(CHUNK)
        var done = false
        try {
            RandomAccessFile(file, "r").use { input ->
                var offset = 0L
                while (offset < total) {
                    val count = minOf(CHUNK.toLong(), total - offset).toInt()
                    input.seek(offset)
                    input.readFully(chunk, 0, count)
                    val range = "bytes $offset-${offset + count - 1}/$total"
                    // The upload URL carries its own authorization; Graph rejects a bearer token on it.
                    execute(path, authorized = false) {
                        Request.Builder().url(uploadUrl).put(chunk.toRequestBody(OCTET_STREAM, 0, count)).header("Content-Range", range)
                    }.close()
                    offset += count
                }
            }
            done = true
        } finally {
            if (!done) runCatching { client.newCall(Request.Builder().url(uploadUrl).delete().build()).execute().close() }
        }
    }

    // region internals

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { blocking(block) }

    private inline fun <T> blocking(block: () -> T): T = try {
        block()
    } catch (e: FsException) {
        throw e
    } catch (e: JSONException) {
        throw FsException("Unexpected answer from OneDrive: ${e.message}", e)
    } catch (e: IOException) {
        throw FsException("Cannot reach OneDrive: ${e.message ?: e.javaClass.simpleName}", e)
    }

    private fun json(response: Response): JSONObject = response.use { JSONObject(it.body.string()) }

    /**
     * Sends a request, with a valid access token unless [authorized] is false. An expired token is
     * refreshed once; throttling (HTTP 429, or 503 with Retry-After) waits as Graph asks, a few times.
     */
    private fun execute(
        path: String,
        conflictPath: String = path,
        authorized: Boolean = true,
        http: OkHttpClient = client,
        build: () -> Request.Builder,
    ): Response {
        var refreshed = false
        var attempts = 0
        while (true) {
            val request = build().apply { if (authorized) header("Authorization", "Bearer ${session.token()}") }.build()
            val response = http.newCall(request).execute()
            if (response.isSuccessful || response.isRedirect) return response
            val retryAfter = response.header("Retry-After")?.toLongOrNull()
            val text = response.use { it.body.string() }
            when {
                response.code == 401 && authorized && !refreshed -> {
                    refreshed = true
                    session.token(forceRefresh = true)
                }
                (response.code == 429 || (response.code == 503 && retryAfter != null)) && attempts < MAX_RETRIES -> {
                    attempts++
                    Thread.sleep(((retryAfter ?: 1L) * 1000).coerceAtMost(MAX_WAIT_MS))
                }
                else -> throw error(response.code, text, path, conflictPath)
            }
        }
    }

    /** Maps a failed call to the matching [FsException], from Graph's `error.code`. */
    private fun error(code: Int, body: String, path: String, conflictPath: String): FsException {
        val error = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
        val kind = error?.optString("code").orEmpty()
        val message = error?.optString("message").orEmpty()
        return when {
            code == 401 -> SignedOutFsException("OneDrive")
            code == 404 || kind == "itemNotFound" -> NotFoundFsException(path)
            code == 409 && kind == "nameAlreadyExists" -> AlreadyExistsFsException(conflictPath)
            code == 403 || kind == "accessDenied" -> AccessDeniedFsException(path)
            code == 507 || kind == "quotaLimitReached" -> FsException("Not enough space in your OneDrive")
            code == 429 -> FsException("OneDrive is busy; try again in a moment")
            message.isNotEmpty() -> FsException("OneDrive: $message")
            else -> FsException("OneDrive: HTTP $code")
        }
    }

    /** `me/drive/root` or `me/drive/root:/a/b`, plus `:/[action]` when given. */
    private fun item(path: String, action: String? = null): HttpUrl {
        val segments = VfsPaths.normalize(path).split('/').filter { it.isNotEmpty() }
        val builder = graphBase.newBuilder().addPathSegments("me/drive")
        if (segments.isEmpty()) {
            builder.addPathSegment("root")
        } else {
            builder.addPathSegment("root:")
            segments.dropLast(1).forEach { builder.addPathSegment(it) }
            builder.addPathSegment(if (action != null) segments.last() + ":" else segments.last())
        }
        if (action != null) builder.addPathSegment(action)
        return builder.build()
    }

    private fun entry(dir: String, json: JSONObject): FileEntry {
        val name = json.getString("name")
        val folder = json.has("folder") || json.has("package") // packages: e.g. OneNote notebooks
        val modified = json.optJSONObject("fileSystemInfo")?.optString("lastModifiedDateTime").orEmpty()
            .ifEmpty { json.optString("lastModifiedDateTime") }
        return FileEntry(
            name = name,
            path = VfsPaths.child(dir, name),
            isDirectory = folder,
            size = if (folder) 0 else json.optLong("size"),
            lastModified = runCatching { Instant.parse(modified).toEpochMilli() }.getOrDefault(0),
        )
    }

    companion object {
        val GRAPH_BASE = "https://graph.microsoft.com/v1.0/".toHttpUrl()

        /** Graph's limit for a single-request upload. */
        const val SIMPLE_UPLOAD_LIMIT = 4 * 1024 * 1024

        /** Upload session piece size; must be a multiple of 320 KiB. */
        const val CHUNK = 32 * 320 * 1024

        private const val CONFLICT = "@microsoft.graph.conflictBehavior"
        private const val SELECT = "id,name,size,folder,package,file,fileSystemInfo,lastModifiedDateTime"
        private const val MAX_RETRIES = 4
        private const val MAX_WAIT_MS = 30_000L
        private val JSON = "application/json".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
    }

    // endregion
}

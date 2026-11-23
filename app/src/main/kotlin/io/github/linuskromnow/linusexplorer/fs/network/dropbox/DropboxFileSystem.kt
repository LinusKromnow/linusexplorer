package io.github.linuskromnow.linusexplorer.fs.network.dropbox

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Dropbox through its HTTP API v2 (no SDK). Paths map one to one; Dropbox itself is
 * case-insensitive. [Connection.password] holds the refresh token, [Connection.share] the app key
 * it was issued for (see [DropboxOAuth]).
 */
class DropboxFileSystem(
    private val connection: Connection,
    baseClient: OkHttpClient = OkHttpClient(),
    private val apiBase: HttpUrl = API_BASE,
    private val contentBase: HttpUrl = CONTENT_BASE,
    private val tokenUrl: HttpUrl = DropboxOAuth.TOKEN_URL,
) : VirtualFileSystem, RangeReadable {
    override val id = connection.fsId
    override val displayName = connection.label
    override val rootPath = VfsPaths.ROOT

    private val client = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private var accessToken: String? = null
    private var expiresAt = 0L

    override suspend fun list(path: String): List<FileEntry> = io {
        val dir = VfsPaths.normalize(path)
        val entries = ArrayList<FileEntry>()
        var page = rpc("files/list_folder", JSONObject().put("path", apiPath(dir)).put("limit", 2000), dir)
        while (true) {
            val items = page.getJSONArray("entries")
            for (i in 0 until items.length()) entry(dir, items.getJSONObject(i))?.let(entries::add)
            if (!page.optBoolean("has_more")) break
            page = rpc("files/list_folder/continue", JSONObject().put("cursor", page.getString("cursor")), dir)
        }
        entries
    }

    override suspend fun stat(path: String): FileEntry = io {
        val p = VfsPaths.normalize(path)
        if (p == VfsPaths.ROOT) return@io FileEntry(VfsPaths.name(p), p, isDirectory = true, size = 0, lastModified = 0)
        val metadata = rpc("files/get_metadata", JSONObject().put("path", apiPath(p)), p)
        entry(VfsPaths.parent(p) ?: VfsPaths.ROOT, metadata) ?: throw NotFoundFsException(p)
    }

    override suspend fun exists(path: String): Boolean = try {
        stat(path)
        true
    } catch (e: NotFoundFsException) {
        false
    }

    override suspend fun createDirectory(path: String) = io {
        rpc("files/create_folder_v2", JSONObject().put("path", apiPath(path)).put("autorename", false), path)
        Unit
    }

    override suspend fun createFile(path: String) = io {
        upload(path, ByteArray(0), 0, mode = "add")
    }

    /** Dropbox keeps deleted files in its own trash for a while, so this can be undone on dropbox.com. */
    override suspend fun delete(path: String) = io {
        rpc("files/delete_v2", JSONObject().put("path", apiPath(path)), path)
        Unit
    }

    override suspend fun rename(path: String, newPath: String) = io {
        val args = JSONObject().put("from_path", apiPath(path)).put("to_path", apiPath(newPath)).put("autorename", false)
        rpc("files/move_v2", args, path, conflictPath = newPath)
        Unit
    }

    override suspend fun moveWithin(path: String, newPath: String): Boolean {
        rename(path, newPath)
        return true
    }

    override suspend fun openInput(path: String): InputStream = io {
        val response = content("files/download", JSONObject().put("path", apiPath(path)), null, path)
        object : FilterInputStream(response.body.byteStream()) {
            override fun close() = response.close()
        }
    }

    override suspend fun readRange(path: String, offset: Long, length: Int): ByteArray = io {
        val range = "bytes=$offset-${offset + length - 1}"
        content("files/download", JSONObject().put("path", apiPath(path)), null, path, range).use { response ->
            if (response.code != 206) throw RangeUnsupportedFsException() // a 200 would be the whole file
            response.body.bytes()
        }
    }

    /**
     * Small files go up in one request. Larger ones become an upload session, sent in
     * [CHUNK]-sized pieces as they are written, so memory use stays bounded.
     */
    override suspend fun openOutput(path: String): OutputStream = withContext(Dispatchers.IO) { UploadStream(path) }

    private inner class UploadStream(private val path: String) : OutputStream() {
        private val buffer = ByteArray(CHUNK)
        private var count = 0
        private var sessionId: String? = null
        private var offset = 0L
        private var closed = false

        override fun write(b: Int) {
            if (count == buffer.size) sendChunk()
            buffer[count++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var from = off
            var left = len
            while (left > 0) {
                if (count == buffer.size) sendChunk()
                val n = minOf(left, buffer.size - count)
                System.arraycopy(b, from, buffer, count, n)
                count += n
                from += n
                left -= n
            }
        }

        private fun sendChunk() = blocking {
            val id = sessionId
            if (id == null) {
                sessionId = content("files/upload_session/start", JSONObject().put("close", false), body(), path)
                    .use { JSONObject(it.body.string()).getString("session_id") }
            } else {
                content("files/upload_session/append_v2", JSONObject().put("cursor", cursor(id)), body(), path).close()
            }
            offset += count
            count = 0
        }

        override fun close() {
            if (closed) return
            closed = true
            val id = sessionId
            if (id == null) {
                blocking { upload(path, buffer, count, mode = "overwrite") }
            } else {
                blocking {
                    val args = JSONObject().put("cursor", cursor(id)).put("commit", commit(path, "overwrite"))
                    content("files/upload_session/finish", args, body(), path).close()
                }
            }
        }

        private fun cursor(id: String) = JSONObject().put("session_id", id).put("offset", offset)

        private fun body() = buffer.toRequestBody(OCTET_STREAM, 0, count)
    }

    // region internals

    private fun upload(path: String, bytes: ByteArray, count: Int, mode: String) {
        content("files/upload", commit(path, mode), bytes.toRequestBody(OCTET_STREAM, 0, count), path).close()
    }

    /** `strict_conflict`: without it, "add" silently succeeds when an identical file is already there. */
    private fun commit(path: String, mode: String) = JSONObject()
        .put("path", apiPath(path))
        .put("mode", mode)
        .put("autorename", false)
        .put("mute", true)
        .put("strict_conflict", mode == "add")

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { blocking(block) }

    private inline fun <T> blocking(block: () -> T): T = try {
        block()
    } catch (e: FsException) {
        throw e
    } catch (e: JSONException) {
        throw FsException("Unexpected answer from Dropbox: ${e.message}", e)
    } catch (e: IOException) {
        throw FsException("Cannot reach Dropbox: ${e.message ?: e.javaClass.simpleName}", e)
    }

    /** An RPC endpoint: JSON arguments in, JSON result out. */
    private fun rpc(endpoint: String, args: JSONObject, path: String, conflictPath: String = path): JSONObject {
        val body = args.toString().toRequestBody(JSON)
        return execute(path, conflictPath) { Request.Builder().url(apiBase.resolve(endpoint)!!).post(body) }
            .use { JSONObject(it.body.string()) }
    }

    /** A content endpoint: arguments in the Dropbox-API-Arg header, file data in the body or the response. */
    private fun content(endpoint: String, args: JSONObject, body: RequestBody?, path: String, range: String? = null): Response =
        execute(path) {
            Request.Builder()
                .url(contentBase.resolve(endpoint)!!)
                .header("Dropbox-API-Arg", headerJson(args))
                .post(body ?: EMPTY)
                .apply { if (range != null) header("Range", range) }
        }

    /**
     * Sends a request with a valid access token. An expired token is refreshed once; rate limits
     * (HTTP 429, or 503 with Retry-After) wait as Dropbox asks, a few times.
     */
    private fun execute(path: String, conflictPath: String = path, build: () -> Request.Builder): Response {
        var refreshed = false
        var attempts = 0
        while (true) {
            val token = token(forceRefresh = false)
            val response = client.newCall(build().header("Authorization", "Bearer $token").build()).execute()
            if (response.isSuccessful) return response
            val retryAfter = response.header("Retry-After")?.toLongOrNull()
            val text = response.use { it.body.string() }
            when {
                response.code == 401 && !refreshed -> {
                    refreshed = true
                    token(forceRefresh = true)
                }
                (response.code == 429 || (response.code == 503 && retryAfter != null)) && attempts < MAX_RETRIES -> {
                    attempts++
                    Thread.sleep(((retryAfter ?: 1L) * 1000).coerceAtMost(MAX_WAIT_MS))
                }
                else -> throw error(response.code, text, path, conflictPath)
            }
        }
    }

    @Synchronized
    private fun token(forceRefresh: Boolean): String {
        val current = accessToken
        if (!forceRefresh && current != null && System.currentTimeMillis() < expiresAt - 60_000) return current
        val refreshToken = connection.password ?: throw SignedOutFsException()
        val tokens = DropboxOAuth.refresh(client, connection.share, refreshToken, tokenUrl)
        accessToken = tokens.accessToken
        expiresAt = tokens.expiresAtMillis
        return tokens.accessToken
    }

    /** Maps a failed call to the matching [FsException], mostly from Dropbox's `error_summary`. */
    private fun error(code: Int, body: String, path: String, conflictPath: String): FsException {
        val summary = runCatching { JSONObject(body).optString("error_summary") }.getOrNull().orEmpty()
        return when {
            code == 401 && "missing_scope" in summary -> FsException("The Dropbox app key lacks a permission (${summary.trimEnd('.', '/')})")
            code == 401 -> SignedOutFsException()
            code == 409 && "not_found" in summary -> NotFoundFsException(path)
            code == 409 && "conflict" in summary -> AlreadyExistsFsException(conflictPath)
            code == 409 && "insufficient_space" in summary -> FsException("Not enough space in your Dropbox")
            code == 409 && ("no_write_permission" in summary || "restricted_content" in summary) -> AccessDeniedFsException(path)
            code == 409 && "malformed_path" in summary -> FsException("Dropbox does not allow this name: ${VfsPaths.name(conflictPath)}")
            code == 409 -> FsException("Dropbox: ${summary.trimEnd('.', '/')}")
            code == 400 -> FsException("Dropbox rejected the request: ${body.take(200)}")
            code == 403 -> AccessDeniedFsException(path)
            code == 429 -> FsException("Dropbox is busy; try again in a moment")
            else -> FsException("Dropbox: HTTP $code")
        }
    }

    /** Dropbox wants "" for the root and "/a/b" otherwise. */
    private fun apiPath(path: String): String = VfsPaths.normalize(path).let { if (it == VfsPaths.ROOT) "" else it }

    private fun entry(dir: String, json: JSONObject): FileEntry? {
        val folder = when (json.optString(".tag")) {
            "folder" -> true
            "file" -> false
            else -> return null // "deleted"
        }
        val name = json.getString("name")
        return FileEntry(
            name = name,
            path = VfsPaths.child(dir, name),
            isDirectory = folder,
            size = if (folder) 0 else json.optLong("size"),
            lastModified = if (folder) 0 else time(json.optString("client_modified").ifEmpty { json.optString("server_modified") }),
        )
    }

    private fun time(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0)

    companion object {
        val API_BASE = "https://api.dropboxapi.com/2/".toHttpUrl()
        val CONTENT_BASE = "https://content.dropboxapi.com/2/".toHttpUrl()

        /** Upload session piece size; Dropbox takes up to 150 MB per request. */
        const val CHUNK = 8 * 1024 * 1024

        private const val MAX_RETRIES = 4
        private const val MAX_WAIT_MS = 30_000L
        private val JSON = "application/json".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody(null)

        /** HTTP headers must be ASCII, so Dropbox expects everything else as \u escapes. */
        fun headerJson(args: JSONObject): String {
            val json = args.toString()
            val out = StringBuilder(json.length)
            for (c in json) if (c.code in 0x20..0x7E) out.append(c) else out.append("\\u%04x".format(c.code))
            return out.toString()
        }
    }

    // endregion
}

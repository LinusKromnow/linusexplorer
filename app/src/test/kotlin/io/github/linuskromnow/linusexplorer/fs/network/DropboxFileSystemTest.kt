package io.github.linuskromnow.linusexplorer.fs.network

import io.github.linuskromnow.linusexplorer.fs.AlreadyExistsFsException
import io.github.linuskromnow.linusexplorer.fs.NotFoundFsException
import io.github.linuskromnow.linusexplorer.fs.network.dropbox.DropboxFileSystem
import io.github.linuskromnow.linusexplorer.fs.network.dropbox.DropboxOAuth
import io.github.linuskromnow.linusexplorer.fs.network.dropbox.SignedOutFsException
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections

/** Runs [DropboxFileSystem] against a small in-memory imitation of the Dropbox API v2. */
class DropboxFileSystemTest {
    private val server = MockWebServer()
    private val fake = FakeDropbox()

    @Before
    fun start() {
        server.dispatcher = fake
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun dropbox(refreshToken: String? = "refresh-1") = DropboxFileSystem(
        Connection(id = "db", name = "", protocol = Protocol.DROPBOX, host = "dropbox.com", password = refreshToken, share = "appkey"),
        OkHttpClient(),
        apiBase = server.url("/api/2/"),
        contentBase = server.url("/content/2/"),
        tokenUrl = server.url("/oauth2/token"),
    )

    @Test
    fun createsListsAndReadsFiles() = runTest {
        val fs = dropbox()
        fs.createDirectory("/Fotos")
        fs.openOutput("/Fotos/Ä b.txt").use { it.write("hello".toByteArray()) }
        fs.createFile("/empty.txt")

        assertEquals(listOf("Fotos", "empty.txt"), fs.list("/").map { it.name }.sorted())
        val file = fs.list("/Fotos").single()
        assertEquals("/Fotos/Ä b.txt", file.path)
        assertEquals(5L, file.size)
        assertTrue(file.lastModified > 0)
        assertTrue(fs.stat("/Fotos").isDirectory)
        assertTrue(fs.stat("/").isDirectory)
        assertEquals("hello", fs.openInput("/Fotos/Ä b.txt").use { String(it.readBytes()) })
        // Non-ASCII names travel \u-escaped in the Dropbox-API-Arg header.
        assertTrue(fake.headerArgs.any { "\\u00c4 b.txt" in it })
        // One refresh for the whole session.
        assertEquals(1, fake.tokensIssued)
    }

    @Test
    fun followsListingPages() = runTest {
        val fs = dropbox()
        repeat(5) { fs.createFile("/f$it") }
        fake.pageSize = 2
        assertEquals(5, fs.list("/").size)
        assertTrue(fake.calls.count { it == "files/list_folder/continue" } >= 2)
    }

    @Test
    fun mapsErrors() = runTest {
        val fs = dropbox()
        fs.createFile("/a.txt")
        expect<AlreadyExistsFsException> { fs.createFile("/a.txt") }
        expect<AlreadyExistsFsException> { fs.createDirectory("/A.TXT") } // Dropbox ignores case
        expect<NotFoundFsException> { fs.stat("/missing") }
        assertFalse(fs.exists("/missing"))
        fs.createFile("/b.txt")
        expect<AlreadyExistsFsException> { fs.rename("/a.txt", "/b.txt") }
        fs.rename("/a.txt", "/c.txt")
        assertEquals(listOf("b.txt", "c.txt"), fs.list("/").map { it.name }.sorted())
        fs.delete("/c.txt")
        assertFalse(fs.exists("/c.txt"))
    }

    @Test
    fun refreshesAnExpiredTokenOnce() = runTest {
        val fs = dropbox()
        fs.list("/")
        fake.expireTokens()
        fs.list("/")
        assertEquals(2, fake.tokensIssued)
    }

    @Test
    fun revokedSignInAsksToSignInAgain() = runTest {
        fake.revoked = true
        expect<SignedOutFsException> { dropbox().list("/") }
        expect<SignedOutFsException> { dropbox(refreshToken = null).list("/") }
    }

    @Test
    fun waitsOutRateLimits() = runTest {
        val fs = dropbox()
        fake.rateLimitNext = 2
        fs.createFile("/x")
        assertTrue(fs.exists("/x"))
    }

    @Test
    fun largeFilesUseAnUploadSession() = runTest {
        val fs = dropbox()
        val data = ByteArray(DropboxFileSystem.CHUNK * 2 + 123) { (it % 251).toByte() }
        fs.openOutput("/big.bin").use { out ->
            var at = 0
            while (at < data.size) {
                val n = minOf(100_000, data.size - at)
                out.write(data, at, n)
                at += n
            }
        }
        assertEquals(listOf("files/upload_session/start", "files/upload_session/append_v2", "files/upload_session/finish"), fake.calls.filter { "upload" in it })
        assertArrayEquals(data, fs.openInput("/big.bin").use { it.readBytes() })
    }

    @Test
    fun readsRanges() = runTest {
        val fs = dropbox()
        fs.openOutput("/r.txt").use { it.write("HELLO WORLD".toByteArray()) }
        assertEquals("WORLD", String(fs.readRange("/r.txt", 6, 5)))
    }

    @Test
    fun pkceChallengeMatchesTheSpec() {
        // RFC 7636, appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", DropboxOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val url = DropboxOAuth.authorizeUrl("key", DropboxOAuth.redirectUri("app.id"), "verifier", "state")
        assertEquals("offline", url.queryParameter("token_access_type"))
        assertEquals("app.id://oauth/dropbox", url.queryParameter("redirect_uri"))
    }

    private suspend inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.simpleName}")
        } catch (e: Throwable) {
            if (e !is T) throw e
        }
    }
}

/** Enough of api.dropboxapi.com, content.dropboxapi.com and the token endpoint for the tests above. */
private class FakeDropbox : Dispatcher() {
    private class Node(val path: String, val folder: Boolean, var data: ByteArray = ByteArray(0))

    private val nodes = HashMap<String, Node>() // keyed by lower-case path, like Dropbox
    private val sessions = HashMap<String, java.io.ByteArrayOutputStream>()
    private var validToken: String? = null

    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
    val headerArgs: MutableList<String> = Collections.synchronizedList(ArrayList())
    var tokensIssued = 0
    var revoked = false
    var pageSize = 1000
    var rateLimitNext = 0

    fun expireTokens() {
        validToken = "expired-for-good"
    }

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        if (path == "/oauth2/token") return token(request)
        if (request.headers["Authorization"] != "Bearer $validToken") {
            return json(401, JSONObject().put("error_summary", "expired_access_token/"))
        }
        if (rateLimitNext > 0) {
            rateLimitNext--
            return MockResponse.Builder().code(429).addHeader("Retry-After", "0").body("").build()
        }
        val endpoint = path.substringAfter("/2/")
        calls += endpoint
        val content = path.startsWith("/content/")
        val args = if (content) {
            val raw = request.headers["Dropbox-API-Arg"]!!
            headerArgs += raw
            JSONObject(raw)
        } else {
            JSONObject(request.body!!.utf8())
        }
        val body = request.body?.toByteArray() ?: ByteArray(0)
        return when (endpoint) {
            "files/list_folder" -> list(args.getString("path"), 0)
            "files/list_folder/continue" -> list(args.getString("cursor").substringBefore('|'), args.getString("cursor").substringAfter('|').toInt())
            "files/get_metadata" -> nodes[key(args.getString("path"))]?.let { json(200, meta(it)) } ?: conflict("path/not_found/")
            "files/create_folder_v2" -> add(Node(args.getString("path"), folder = true))?.let { json(200, JSONObject().put("metadata", meta(it))) } ?: conflict("path/conflict/folder/")
            "files/delete_v2" -> nodes.keys.filter { it == key(args.getString("path")) || it.startsWith(key(args.getString("path")) + "/") }
                .also { if (it.isEmpty()) return conflict("path_lookup/not_found/") }
                .let { keys -> keys.forEach(nodes::remove); json(200, JSONObject()) }
            "files/move_v2" -> {
                val from = nodes[key(args.getString("from_path"))] ?: return conflict("from_lookup/not_found/")
                if (nodes.containsKey(key(args.getString("to_path")))) return conflict("to/conflict/file/")
                nodes.remove(key(from.path))
                add(Node(args.getString("to_path"), from.folder, from.data))
                json(200, JSONObject())
            }
            "files/upload" -> upload(args, body)
            "files/upload_session/start" -> {
                val id = "s${sessions.size}"
                sessions[id] = java.io.ByteArrayOutputStream().apply { write(body) }
                json(200, JSONObject().put("session_id", id))
            }
            "files/upload_session/append_v2" -> {
                val cursor = args.getJSONObject("cursor")
                val buffer = sessions.getValue(cursor.getString("session_id"))
                if (cursor.getLong("offset") != buffer.size().toLong()) return conflict("incorrect_offset/")
                buffer.write(body)
                json(200, JSONObject())
            }
            "files/upload_session/finish" -> {
                val cursor = args.getJSONObject("cursor")
                val buffer = sessions.remove(cursor.getString("session_id"))!!
                if (cursor.getLong("offset") != buffer.size().toLong()) return conflict("lookup_failed/incorrect_offset/")
                buffer.write(body)
                upload(args.getJSONObject("commit"), buffer.toByteArray())
            }
            "files/download" -> {
                val node = nodes[key(args.getString("path"))] ?: return conflict("path/not_found/")
                val range = request.headers["Range"]
                if (range == null) {
                    MockResponse.Builder().code(200).body(okio.Buffer().write(node.data)).build()
                } else {
                    val (from, to) = range.removePrefix("bytes=").split('-').map { it.toInt() }
                    MockResponse.Builder().code(206).body(okio.Buffer().write(node.data, from, minOf(to + 1, node.data.size) - from)).build()
                }
            }
            else -> MockResponse.Builder().code(400).body("Unknown endpoint $endpoint").build()
        }
    }

    private fun token(request: RecordedRequest): MockResponse {
        val form = request.body!!.utf8()
        if (revoked || "client_id=appkey" !in form) return json(400, JSONObject().put("error", "invalid_grant"))
        tokensIssued++
        validToken = "access-$tokensIssued"
        return json(200, JSONObject().put("access_token", validToken).put("expires_in", 14400).put("token_type", "bearer"))
    }

    private fun list(dir: String, from: Int): MockResponse {
        val prefix = key(dir) + "/"
        val children = nodes.values.filter { key(it.path).startsWith(prefix) && '/' !in key(it.path).removePrefix(prefix) }.sortedBy { it.path }
        val page = children.drop(from).take(pageSize)
        val more = from + page.size < children.size
        return json(
            200,
            JSONObject()
                .put("entries", JSONArray(page.map(::meta)))
                .put("has_more", more)
                .put("cursor", "$dir|${from + page.size}"),
        )
    }

    private fun upload(commit: JSONObject, data: ByteArray): MockResponse {
        val path = commit.getString("path")
        val existing = nodes[key(path)]
        if (existing != null && (commit.getString("mode") == "add" || existing.folder)) return conflict("path/conflict/file/")
        val node = existing ?: add(Node(path, folder = false))!!
        node.data = data
        return json(200, meta(node))
    }

    private fun add(node: Node): Node? {
        if (nodes.containsKey(key(node.path))) return null
        nodes[key(node.path)] = node
        return node
    }

    private fun meta(node: Node): JSONObject = JSONObject()
        .put(".tag", if (node.folder) "folder" else "file")
        .put("name", node.path.substringAfterLast('/'))
        .put("path_display", node.path)
        .apply {
            if (!node.folder) {
                put("size", node.data.size)
                put("client_modified", "2026-11-23T10:00:00Z")
                put("server_modified", "2026-11-23T10:00:01Z")
            }
        }

    private fun key(path: String) = path.lowercase()

    private fun conflict(summary: String) = json(409, JSONObject().put("error_summary", summary))

    private fun json(code: Int, body: JSONObject) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body.toString()).build()
}

package io.github.linuskromnow.linusexplorer.fs.network

import io.github.linuskromnow.linusexplorer.fs.AlreadyExistsFsException
import io.github.linuskromnow.linusexplorer.fs.NotFoundFsException
import io.github.linuskromnow.linusexplorer.fs.network.cloud.OAuth
import io.github.linuskromnow.linusexplorer.fs.network.cloud.SignedOutFsException
import io.github.linuskromnow.linusexplorer.fs.network.onedrive.OneDrive
import io.github.linuskromnow.linusexplorer.fs.network.onedrive.OneDriveFileSystem
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections

/** Runs [OneDriveFileSystem] against a small in-memory imitation of Microsoft Graph. */
class OneDriveFileSystemTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val fake = FakeGraph { server.url(it) }
    private val rotated = Collections.synchronizedList(ArrayList<String>())

    @Before
    fun start() {
        server.dispatcher = fake
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun oneDrive(refreshToken: String? = fake.currentRefresh) = OneDriveFileSystem(
        Connection(id = "od", name = "", protocol = Protocol.ONEDRIVE, host = "onedrive.live.com", password = refreshToken, share = "client"),
        spoolDir = temp.newFolder(),
        baseClient = OkHttpClient(),
        graphBase = server.url("/v1.0/"),
        tokenUrl = server.url("/token"),
        onRefreshTokenChanged = { rotated += it },
    )

    @Test
    fun createsListsAndReadsFiles() = runTest {
        val fs = oneDrive()
        fs.createDirectory("/Fotos")
        fs.openOutput("/Fotos/Ä #1.txt").use { it.write("hello".toByteArray()) }
        fs.createFile("/empty.txt")

        assertEquals(listOf("Fotos", "empty.txt"), fs.list("/").map { it.name }.sorted())
        val file = fs.list("/Fotos").single()
        assertEquals("/Fotos/Ä #1.txt", file.path)
        assertEquals(5L, file.size)
        assertEquals(1795341600000L, file.lastModified) // fileSystemInfo, not the server's own time
        assertTrue(fs.stat("/Fotos").isDirectory)
        assertTrue(fs.stat("/").isDirectory)
        assertEquals("hello", fs.openInput("/Fotos/Ä #1.txt").use { String(it.readBytes()) })
        // Downloads are redirected to a pre-authorized URL, which must not get the bearer token.
        assertTrue(fake.downloadAuthHeaders.isNotEmpty())
        assertTrue(fake.downloadAuthHeaders.all { it == null })
        assertEquals("WORLD", run {
            fs.openOutput("/r.txt").use { it.write("HELLO WORLD".toByteArray()) }
            String(fs.readRange("/r.txt", 6, 5))
        })
    }

    @Test
    fun followsListingPages() = runTest {
        val fs = oneDrive()
        repeat(5) { fs.createFile("/f$it") }
        fake.pageSize = 2
        assertEquals(5, fs.list("/").size)
    }

    @Test
    fun mapsErrors() = runTest {
        val fs = oneDrive()
        fs.createFile("/a.txt")
        expect<AlreadyExistsFsException> { fs.createFile("/a.txt") }
        expect<AlreadyExistsFsException> { fs.createDirectory("/A.TXT") }
        expect<NotFoundFsException> { fs.stat("/missing") }
        assertFalse(fs.exists("/missing"))
        fs.createFile("/b.txt")
        expect<AlreadyExistsFsException> { fs.rename("/a.txt", "/b.txt") }
        fs.createDirectory("/dir")
        fs.rename("/a.txt", "/dir/c.txt") // a move: goes through the parent's id
        assertEquals(listOf("c.txt"), fs.list("/dir").map { it.name })
        fs.delete("/dir")
        assertFalse(fs.exists("/dir/c.txt"))
    }

    @Test
    fun storesRotatedRefreshTokens() = runTest {
        val first = fake.currentRefresh
        val fs = oneDrive()
        fs.list("/")
        assertEquals(listOf(fake.currentRefresh), rotated)
        fake.expireAccessTokens()
        fs.list("/") // refreshes with the rotated token, not the original one
        assertEquals(2, rotated.size)
        // The original token was used up by the first refresh.
        expect<SignedOutFsException> { oneDrive(refreshToken = first).list("/") }
    }

    @Test
    fun largeFilesUseAnUploadSession() = runTest {
        val fs = oneDrive()
        val data = ByteArray(OneDriveFileSystem.CHUNK + OneDriveFileSystem.SIMPLE_UPLOAD_LIMIT + 77) { (it % 241).toByte() }
        fs.openOutput("/big.bin").use { out ->
            var at = 0
            while (at < data.size) {
                val n = minOf(65_536, data.size - at)
                out.write(data, at, n)
                at += n
            }
        }
        assertEquals(2, fake.chunkRanges.size)
        assertTrue(fake.chunkRanges.first().startsWith("bytes 0-${OneDriveFileSystem.CHUNK - 1}/${data.size}"))
        assertTrue(fake.chunkAuthHeaders.all { it == null })
        assertArrayEquals(data, fs.openInput("/big.bin").use { it.readBytes() })
        assertTrue(temp.root.walk().none { it.name.endsWith(".part") }) // spool file cleaned up
    }

    @Test
    fun signInParameters() {
        val url = OAuth.authorizeUrl(OneDrive.ENDPOINTS, "client", OneDrive.ENDPOINTS.redirectUri("app.id"), "verifier", "state")
        assertEquals("app.id://oauth/onedrive", url.queryParameter("redirect_uri"))
        assertTrue("offline_access" in url.queryParameter("scope")!!)
        assertNull(url.queryParameter("token_access_type"))
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

/** Enough of graph.microsoft.com (path addressing) and the token endpoint for the tests above. */
private class FakeGraph(private val url: (String) -> okhttp3.HttpUrl) : Dispatcher() {
    private class Node(var path: String, val folder: Boolean, val id: String, var data: ByteArray = ByteArray(0))

    private val nodes = HashMap<String, Node>() // keyed by lower-case path; OneDrive ignores case
    private val sessions = HashMap<String, Pair<String, java.io.ByteArrayOutputStream>>()
    private var nextId = 1
    private var validAccess: String? = null
    private var refreshCount = 0

    var currentRefresh = "refresh-0"
        private set
    var pageSize = 1000
    val downloadAuthHeaders: MutableList<String?> = Collections.synchronizedList(ArrayList())
    val chunkRanges: MutableList<String> = Collections.synchronizedList(ArrayList())
    val chunkAuthHeaders: MutableList<String?> = Collections.synchronizedList(ArrayList())

    fun expireAccessTokens() {
        validAccess = "expired"
    }

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
        val target = request.url
        val path = target.encodedPath
        when {
            path == "/token" -> return token(request)
            path.startsWith("/dl/") -> {
                downloadAuthHeaders += request.headers["Authorization"]
                val node = nodes.values.first { it.id == path.removePrefix("/dl/") }
                val range = request.headers["Range"] ?: return MockResponse.Builder().code(200).body(okio.Buffer().write(node.data)).build()
                val (from, to) = range.removePrefix("bytes=").split('-').map { it.toInt() }
                return MockResponse.Builder().code(206).body(okio.Buffer().write(node.data, from, minOf(to + 1, node.data.size) - from)).build()
            }
            path.startsWith("/upload/") -> return chunk(request, path.removePrefix("/upload/"))
        }
        if (request.headers["Authorization"] != "Bearer $validAccess") {
            return error(401, "InvalidAuthenticationToken")
        }
        // /v1.0/me/drive/root[/action] or /v1.0/me/drive/root:/a/b[:/action]
        val rest = target.pathSegments.drop(3) // v1.0, me, drive
        val (itemPath, action) = if (rest.first() == "root") {
            "" to rest.getOrNull(1)
        } else {
            val segments = rest.drop(1)
            val last = segments.indexOfFirst { it.endsWith(":") }
            if (last < 0) "/" + segments.joinToString("/") to null
            else "/" + segments.take(last + 1).joinToString("/").removeSuffix(":") to segments.getOrNull(last + 1)
        }
        val conflict = target.queryParameter("@microsoft.graph.conflictBehavior")
        return when (request.method to action) {
            "GET" to null -> if (itemPath.isEmpty()) json(200, JSONObject().put("id", "root").put("name", "root").put("folder", JSONObject()))
            else nodes[key(itemPath)]?.let { json(200, meta(it)) } ?: error(404, "itemNotFound")
            "GET" to "children" -> {
                val skip = target.queryParameter("skip")?.toInt() ?: 0
                val prefix = key(itemPath) + "/"
                val children = nodes.values.filter { key(it.path).startsWith(prefix) && '/' !in key(it.path).removePrefix(prefix) }.sortedBy { it.path }
                val page = children.drop(skip).take(pageSize)
                val body = JSONObject().put("value", JSONArray(page.map(::meta)))
                if (skip + page.size < children.size) {
                    body.put("@odata.nextLink", target.newBuilder().setQueryParameter("skip", (skip + page.size).toString()).build().toString())
                }
                json(200, body)
            }
            "POST" to "children" -> {
                val args = JSONObject(request.body!!.utf8())
                assertEquals("fail", args.getString("@microsoft.graph.conflictBehavior"))
                add("$itemPath/${args.getString("name")}", folder = true)?.let { json(201, meta(it)) } ?: error(409, "nameAlreadyExists")
            }
            "PUT" to "content" -> {
                val existing = nodes[key(itemPath)]
                if (existing != null && conflict == "fail") return error(409, "nameAlreadyExists")
                val node = existing ?: add(itemPath, folder = false)!!
                node.data = request.body?.toByteArray() ?: ByteArray(0)
                json(if (existing == null) 201 else 200, meta(node))
            }
            "POST" to "createUploadSession" -> {
                val id = "s${sessions.size}"
                sessions[id] = itemPath to java.io.ByteArrayOutputStream()
                json(200, JSONObject().put("uploadUrl", url("/upload/$id").toString()))
            }
            "DELETE" to null -> {
                val k = key(itemPath)
                if (nodes.remove(k) == null) return error(404, "itemNotFound")
                nodes.keys.filter { it.startsWith("$k/") }.forEach(nodes::remove)
                MockResponse.Builder().code(204).build()
            }
            "PATCH" to null -> {
                val node = nodes[key(itemPath)] ?: return error(404, "itemNotFound")
                val args = JSONObject(request.body!!.utf8())
                val parentId = args.optJSONObject("parentReference")?.getString("id")
                val parent = when (parentId) {
                    null -> itemPath.substringBeforeLast('/')
                    "root" -> ""
                    else -> nodes.values.first { it.id == parentId }.path
                }
                val newPath = "$parent/${args.getString("name")}"
                if (nodes.containsKey(key(newPath))) return error(409, "nameAlreadyExists")
                val old = key(node.path)
                nodes.keys.filter { it == old || it.startsWith("$old/") }.forEach { k ->
                    val moved = nodes.remove(k)!!
                    moved.path = newPath + moved.path.substring(old.length)
                    nodes[key(moved.path)] = moved
                }
                json(200, meta(node))
            }
            "GET" to "content" -> {
                val node = nodes[key(itemPath)] ?: return error(404, "itemNotFound")
                MockResponse.Builder().code(302).addHeader("Location", url("/dl/${node.id}").toString()).build()
            }
            else -> MockResponse.Builder().code(400).body("Unknown ${request.method} $path").build()
        }
    }

    private fun chunk(request: RecordedRequest, id: String): MockResponse {
        chunkAuthHeaders += request.headers["Authorization"]
        val range = request.headers["Content-Range"]!!
        chunkRanges += range
        val (path, buffer) = sessions.getValue(id)
        val (span, total) = range.removePrefix("bytes ").split('/')
        val start = span.substringBefore('-').toLong()
        if (start != buffer.size().toLong()) return error(416, "invalidRange")
        buffer.write(request.body!!.toByteArray())
        if (buffer.size().toLong() < total.toLong()) {
            return json(202, JSONObject().put("nextExpectedRanges", JSONArray().put("${buffer.size()}-")))
        }
        sessions.remove(id)
        val node = nodes[key(path)] ?: add(path, folder = false)!!
        node.data = buffer.toByteArray()
        return json(201, meta(node))
    }

    private fun token(request: RecordedRequest): MockResponse {
        val form = request.body!!.utf8()
        if ("refresh_token=$currentRefresh" !in form || "client_id=client" !in form) {
            return json(400, JSONObject().put("error", "invalid_grant").put("error_description", "AADSTS70000: expired"))
        }
        refreshCount++
        currentRefresh = "refresh-$refreshCount" // Microsoft rotates refresh tokens on every use
        validAccess = "access-$refreshCount"
        return json(200, JSONObject().put("access_token", validAccess).put("refresh_token", currentRefresh).put("expires_in", 3600))
    }

    private fun add(path: String, folder: Boolean): Node? {
        if (nodes.containsKey(key(path))) return null
        return Node(path, folder, "id${nextId++}").also { nodes[key(path)] = it }
    }

    private fun meta(node: Node): JSONObject = JSONObject()
        .put("id", node.id)
        .put("name", node.path.substringAfterLast('/'))
        .put("lastModifiedDateTime", "2026-11-23T10:00:01Z")
        .put("fileSystemInfo", JSONObject().put("lastModifiedDateTime", "2026-11-22T10:00:00Z"))
        .apply {
            if (node.folder) put("folder", JSONObject().put("childCount", 0)) else put("file", JSONObject()).put("size", node.data.size)
        }

    private fun key(path: String) = path.lowercase()

    private fun error(code: Int, kind: String) =
        json(code, JSONObject().put("error", JSONObject().put("code", kind).put("message", kind)))

    private fun json(code: Int, body: JSONObject) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body.toString()).build()

    private fun assertEquals(expected: String, actual: String) = org.junit.Assert.assertEquals(expected, actual)
}

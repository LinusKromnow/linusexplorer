package io.github.linuskromnow.linusexplorer.fs.network

import io.github.linuskromnow.linusexplorer.fs.FsException
import io.github.linuskromnow.linusexplorer.fs.PasswordRequiredFsException
import io.github.linuskromnow.linusexplorer.fs.RangeUnsupportedFsException
import io.github.linuskromnow.linusexplorer.fs.network.webdav.WebDavFileSystem
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Credentials
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Protocol-level tests against a scripted HTTP server (runs on CI, no real WebDAV server needed). */
class WebDavFileSystemTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun dav(password: String? = "secret") = WebDavFileSystem(
        Connection(
            id = "dav", name = "dav", protocol = Protocol.WEBDAV_HTTP,
            host = server.hostName, port = server.port, username = "linus", password = password,
            share = "/remote.php/dav/files/linus",
        ),
        KnownHosts(File(temp.root, "known_hosts")),
    )

    /** Nextcloud answers with absolute, percent-encoded hrefs and puts unknown props in a 404 propstat. */
    private val nextcloudListing = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns">
 <d:response><d:href>/remote.php/dav/files/linus/Photos/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><d:getlastmodified>Sat, 24 Oct 2026 10:00:00 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  <d:propstat><d:prop><d:getcontentlength/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
 </d:response>
 <d:response><d:href>/remote.php/dav/files/linus/Photos/Mein%20Foto%20%C3%A4.jpg</d:href>
  <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>12345</d:getcontentlength><d:getlastmodified>Fri, 23 Oct 2026 08:30:00 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
 <d:response><d:href>/remote.php/dav/files/linus/Photos/Trip/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
 </d:response>
</d:multistatus>"""

    @Test
    fun rangeReadsAskForPartialContent() = runTest {
        server.enqueue(MockResponse.Builder().code(206).body("WORLD").build())
        assertEquals("WORLD", String(dav().readRange("/Docs/a.txt", 6, 5)))
        assertEquals("bytes=6-10", server.takeRequest().headers["Range"])

        // A server that ignores Range sends the whole file with 200; that must not be taken as the range.
        server.enqueue(MockResponse.Builder().code(200).body("HELLO WORLD").build())
        try {
            dav().readRange("/Docs/a.txt", 6, 5)
            fail("a full response was accepted as a range")
        } catch (e: RangeUnsupportedFsException) {
            // expected: the caller downloads the whole file instead
        }
    }

    @Test
    fun listsAndDecodesNames() = runTest {
        server.enqueue(MockResponse.Builder().code(207).body(nextcloudListing).build())
        val entries = dav().list("/Photos")
        assertEquals(listOf("/Photos/Mein Foto ä.jpg", "/Photos/Trip"), entries.map { it.path })
        val photo = entries.first()
        assertEquals(12345L, photo.size)
        assertEquals(1792744200000L, photo.lastModified) // Fri, 23 Oct 2026 08:30:00 GMT
        assertTrue(entries[1].isDirectory)

        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("/remote.php/dav/files/linus/Photos/", request.url.encodedPath)
        assertEquals("1", request.headers["Depth"])
        assertEquals(Credentials.basic("linus", "secret"), request.headers["Authorization"])
    }

    @Test
    fun moveUsesDestinationWithoutOverwrite() = runTest {
        val fileStat = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>/remote.php/dav/files/linus/a%20b.txt</d:href>
<d:propstat><d:prop><d:resourcetype/><d:getcontentlength>3</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        server.enqueue(MockResponse.Builder().code(207).body(fileStat).build())
        server.enqueue(MockResponse.Builder().code(201).build())
        dav().rename("/a b.txt", "/new name.txt")
        server.takeRequest() // PROPFIND for the type
        val move = server.takeRequest()
        assertEquals("MOVE", move.method)
        assertEquals("/remote.php/dav/files/linus/a%20b.txt", move.url.encodedPath)
        assertTrue(move.headers["Destination"]!!.endsWith("/remote.php/dav/files/linus/new%20name.txt"))
        assertEquals("F", move.headers["Overwrite"])
    }

    @Test
    fun streamsUploads() = runTest {
        server.enqueue(MockResponse.Builder().code(201).build())
        val data = ByteArray(1_000_000) { (it % 251).toByte() }
        dav().openOutput("/big.bin").use { out -> data.toList().chunked(65_536).forEach { out.write(it.toByteArray()) } }
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals(data.size.toLong(), put.body!!.size.toLong())
        assertTrue(put.body!!.toByteArray().contentEquals(data))
    }

    @Test
    fun unauthorizedAsksForPassword() = runTest {
        server.enqueue(MockResponse.Builder().code(401).build())
        try {
            dav(password = "wrong").list("/")
            fail("401 accepted")
        } catch (e: PasswordRequiredFsException) {
            assertTrue(e.wrongPassword)
        }
        try {
            dav(password = null).list("/")
            fail("connected without password")
        } catch (e: PasswordRequiredFsException) {
            assertEquals(false, e.wrongPassword)
        }
    }

    @Test
    fun redirectsAreReportedNotFollowed() = runTest {
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", "https://elsewhere.example/dav/").build())
        try {
            dav().list("/")
            fail("redirect followed")
        } catch (e: FsException) {
            assertTrue(e.message!!.contains("elsewhere.example"))
        }
    }
}

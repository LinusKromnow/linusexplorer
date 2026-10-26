package io.github.linuskromnow.linusexplorer.fs.network

import io.github.linuskromnow.linusexplorer.fs.FsException
import io.github.linuskromnow.linusexplorer.fs.NotFoundFsException
import io.github.linuskromnow.linusexplorer.fs.PasswordRequiredFsException
import io.github.linuskromnow.linusexplorer.fs.network.s3.S3FileSystem
import io.github.linuskromnow.linusexplorer.fs.RangeUnsupportedFsException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Protocol-level tests against a scripted HTTP server (runs on CI, no real S3 needed). */
class S3FileSystemTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun s3() = S3FileSystem(
        Connection(
            id = "s3", name = "", protocol = Protocol.S3_HTTP,
            host = server.hostName, port = server.port, username = "AKID", password = "secret",
            share = "photos", region = "eu-central-1",
        ),
        KnownHosts(File(temp.root, "known_hosts")),
    )

    private fun xml(code: Int, body: String) = MockResponse.Builder().code(code).body(body).build()

    @Test
    fun listsFoldersAndFilesAcrossPages() = runTest {
        server.enqueue(
            xml(
                200,
                """<?xml version="1.0" encoding="UTF-8"?>
<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
  <Name>photos</Name><Prefix>2026/</Prefix><IsTruncated>true</IsTruncated>
  <NextContinuationToken>tok+/=</NextContinuationToken>
  <Contents><Key>2026/</Key><Size>0</Size></Contents>
  <Contents><Key>2026/Ä b.jpg</Key><LastModified>2026-10-24T10:00:00.000Z</LastModified><Size>12345</Size></Contents>
  <CommonPrefixes><Prefix>2026/trip/</Prefix></CommonPrefixes>
</ListBucketResult>""",
            ),
        )
        server.enqueue(
            xml(
                200,
                """<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><IsTruncated>false</IsTruncated>
  <Contents><Key>2026/z.txt</Key><Size>3</Size></Contents></ListBucketResult>""",
            ),
        )
        val entries = s3().list("/2026")
        assertEquals(listOf("trip", "Ä b.jpg", "z.txt"), entries.map { it.name })
        assertTrue(entries[0].isDirectory)
        assertEquals(12345L, entries[1].size)
        assertEquals(1792836000000L, entries[1].lastModified)

        val first = server.takeRequest()
        assertEquals("/photos/?delimiter=%2F&list-type=2&max-keys=1000&prefix=2026%2F", first.target)
        val auth = first.headers["Authorization"]!!
        assertTrue(auth, auth.startsWith("AWS4-HMAC-SHA256 Credential=AKID/") && "/eu-central-1/s3/aws4_request" in auth)
        assertTrue(server.takeRequest().target.contains("continuation-token=tok%2B%2F%3D"))
    }

    @Test
    fun rangeReadsAreSignedPartialGets() = runTest {
        server.enqueue(MockResponse.Builder().code(206).body("WORLD").build())
        assertEquals("WORLD", String(s3().readRange("/docs/a.txt", 6, 5)))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("bytes=6-10", request.headers["Range"])
        assertTrue(request.headers["Authorization"].orEmpty().contains("range"))

        server.enqueue(MockResponse.Builder().code(200).body("HELLO WORLD").build())
        try {
            s3().readRange("/docs/a.txt", 6, 5)
            fail("a full response was accepted as a range")
        } catch (e: RangeUnsupportedFsException) {
            // expected
        }
    }

    @Test
    fun badSecretAsksForPassword() = runTest {
        server.enqueue(xml(403, "<Error><Code>SignatureDoesNotMatch</Code><Message>nope</Message></Error>"))
        try {
            s3().list("/")
            fail("listing succeeded")
        } catch (e: PasswordRequiredFsException) {
            assertTrue(e.wrongPassword)
        }
    }

    @Test
    fun wrongRegionIsExplained() = runTest {
        server.enqueue(
            xml(400, "<Error><Code>AuthorizationHeaderMalformed</Code><Message>m</Message><Region>us-west-2</Region></Error>"),
        )
        try {
            s3().list("/")
            fail("listing succeeded")
        } catch (e: FsException) {
            assertTrue(e.message!!, e.message!!.contains("us-west-2"))
        }
    }

    @Test
    fun statFallsBackToPrefixAndReportsMissing() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build()) // HEAD folder
        server.enqueue(xml(200, "<ListBucketResult><CommonPrefixes><Prefix>dir/sub/</Prefix></CommonPrefixes></ListBucketResult>"))
        server.enqueue(MockResponse.Builder().code(404).build()) // HEAD missing
        server.enqueue(xml(200, "<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>"))
        val fs = s3()
        assertTrue(fs.stat("/dir").isDirectory)
        try {
            fs.stat("/missing")
            fail("missing file found")
        } catch (e: NotFoundFsException) {
            // expected
        }
    }

    @Test
    fun copyErrorInside200Fails() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build()) // HEAD target
        server.enqueue(xml(200, "<ListBucketResult/>")) // target prefix: absent
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Length", "3").build()) // HEAD source
        server.enqueue(xml(200, "<Error><Code>InternalError</Code><Message>try again</Message></Error>"))
        try {
            s3().rename("/a.txt", "/b.txt")
            fail("rename succeeded")
        } catch (e: FsException) {
            assertTrue(e.message!!, e.message!!.contains("try again"))
        }
        repeat(3) { server.takeRequest() }
        assertEquals("/photos/a.txt", server.takeRequest().headers["x-amz-copy-source"])
    }

    @Test
    fun guessesRegionFromHost() {
        assertEquals("eu-central-1", S3FileSystem.guessRegion("s3.eu-central-1.amazonaws.com"))
        assertEquals("us-west-002", S3FileSystem.guessRegion("s3.us-west-002.backblazeb2.com"))
        assertEquals("auto", S3FileSystem.guessRegion("abc123.r2.cloudflarestorage.com"))
        assertEquals("us-east-1", S3FileSystem.guessRegion("192.168.1.5"))
    }
}

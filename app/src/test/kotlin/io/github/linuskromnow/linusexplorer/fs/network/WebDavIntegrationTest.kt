package io.github.linuskromnow.linusexplorer.fs.network

import io.github.linuskromnow.linusexplorer.fs.FileSystemRegistry
import io.github.linuskromnow.linusexplorer.fs.FileTransfer
import io.github.linuskromnow.linusexplorer.fs.InMemoryFileSystem
import io.github.linuskromnow.linusexplorer.fs.Location
import io.github.linuskromnow.linusexplorer.fs.NotFoundFsException
import io.github.linuskromnow.linusexplorer.fs.network.webdav.WebDavFileSystem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * Runs against a real WebDAV server over plain HTTP. Opt-in via environment:
 * WEBDAV_TEST_HOST, WEBDAV_TEST_PORT, WEBDAV_TEST_PATH, WEBDAV_TEST_USER, WEBDAV_TEST_PASSWORD.
 * Works in a fresh "linusexplorer-test-*" folder and removes it.
 */
class WebDavIntegrationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val host: String? = System.getenv("WEBDAV_TEST_HOST")

    @Before
    fun requireServer() = assumeTrue("set WEBDAV_TEST_HOST to run WebDAV integration tests", host != null)

    @Test
    fun roundTrip() = runTest {
        val fs = WebDavFileSystem(
            Connection(
                id = "dav", name = "dav", protocol = Protocol.WEBDAV_HTTP,
                host = host!!, port = System.getenv("WEBDAV_TEST_PORT")!!.toInt(),
                username = System.getenv("WEBDAV_TEST_USER").orEmpty(), password = System.getenv("WEBDAV_TEST_PASSWORD"),
                share = System.getenv("WEBDAV_TEST_PATH").orEmpty(),
            ),
            KnownHosts(File(temp.root, "known_hosts")),
        )
        val base = "/linusexplorer-test-${System.currentTimeMillis()}"
        val phone = InMemoryFileSystem("phone").apply {
            write("/Fotos/Ä b.jpg", "jpeg bytes")
            write("/Fotos/trip/b.txt", "notes")
        }
        val big = Random(4).nextBytes(3 * 1024 * 1024 + 5)
        phone.openOutput("/big.bin").use { it.write(big) }
        val back = InMemoryFileSystem("back")
        val transfer = FileTransfer(FileSystemRegistry().apply { register(phone); register(fs); register(back) })
        try {
            fs.createDirectory(base)
            transfer.run(listOf(Location("phone", "/Fotos"), Location("phone", "/big.bin")), Location(fs.id, base), move = false) {}
            assertEquals(listOf("Fotos", "big.bin"), fs.list(base).map { it.name }.sorted())
            assertEquals(listOf("trip", "Ä b.jpg"), fs.list("$base/Fotos").map { it.name }.sorted())
            assertEquals(big.size.toLong(), fs.stat("$base/big.bin").size)
            transfer.run(listOf(Location(fs.id, "$base/big.bin")), Location("back", "/"), move = false) {}
            assertArrayEquals(big, back.openInput("/big.bin").readBytes())
            fs.rename("$base/Fotos", "$base/Umbenannt")
            assertEquals("notes", fs.openInput("$base/Umbenannt/trip/b.txt").use { String(it.readBytes()) })
            assertTrue(fs.exists("$base/Umbenannt/Ä b.jpg"))
            assertFalse(fs.exists("$base/Fotos"))
            try {
                fs.stat("$base/nope")
                fail("missing file found")
            } catch (e: NotFoundFsException) {
                // expected
            }
        } finally {
            runCatching { fs.delete(base) }
        }
        assertFalse(fs.exists(base))
    }
}

package io.github.linuskromnow.linusexplorer.fs.network

import io.github.linuskromnow.linusexplorer.fs.FileSystemRegistry
import io.github.linuskromnow.linusexplorer.fs.FileTransfer
import io.github.linuskromnow.linusexplorer.fs.InMemoryFileSystem
import io.github.linuskromnow.linusexplorer.fs.Location
import io.github.linuskromnow.linusexplorer.fs.NotFoundFsException
import io.github.linuskromnow.linusexplorer.fs.network.s3.S3FileSystem
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
 * Runs against a real S3-compatible server over plain HTTP (MinIO, moto...). Opt-in via environment:
 * S3_TEST_HOST, S3_TEST_PORT, S3_TEST_BUCKET, S3_TEST_ACCESS_KEY, S3_TEST_SECRET_KEY, optionally S3_TEST_REGION.
 * Works below a fresh "linusexplorer-test-*" prefix and removes it.
 */
class S3IntegrationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val host: String? = System.getenv("S3_TEST_HOST")

    @Before
    fun requireServer() = assumeTrue("set S3_TEST_HOST to run S3 integration tests", host != null)

    @Test
    fun roundTrip() = runTest {
        val fs = S3FileSystem(
            Connection(
                id = "s3", name = "s3", protocol = Protocol.S3_HTTP,
                host = host!!, port = System.getenv("S3_TEST_PORT")!!.toInt(),
                username = System.getenv("S3_TEST_ACCESS_KEY")!!, password = System.getenv("S3_TEST_SECRET_KEY"),
                share = System.getenv("S3_TEST_BUCKET")!!, region = System.getenv("S3_TEST_REGION").orEmpty(),
            ),
            KnownHosts(File(temp.root, "known_hosts")),
        )
        val base = "/linusexplorer-test-${System.currentTimeMillis()}"
        val phone = InMemoryFileSystem("phone").apply {
            write("/Fotos/Ä b+c.jpg", "jpeg bytes")
            write("/Fotos/trip/b.txt", "notes")
        }
        // Bigger than two parts, so the multipart path is exercised.
        val big = Random(4).nextBytes(17 * 1024 * 1024 + 5)
        phone.openOutput("/big.bin").use { it.write(big) }
        val back = InMemoryFileSystem("back")
        val transfer = FileTransfer(FileSystemRegistry().apply { register(phone); register(fs); register(back) })
        try {
            fs.createDirectory(base)
            fs.createDirectory("$base/empty")
            assertEquals(emptyList<String>(), fs.list("$base/empty").map { it.name })
            transfer.run(listOf(Location("phone", "/Fotos"), Location("phone", "/big.bin")), Location(fs.id, base), move = false) {}
            assertEquals(listOf("Fotos", "big.bin", "empty"), fs.list(base).map { it.name }.sorted())
            assertEquals(listOf("trip", "Ä b+c.jpg"), fs.list("$base/Fotos").map { it.name }.sorted())
            assertEquals(big.size.toLong(), fs.stat("$base/big.bin").size)
            transfer.run(listOf(Location(fs.id, "$base/big.bin")), Location("back", "/"), move = false) {}
            assertArrayEquals(big, back.openInput("/big.bin").readBytes())
            fs.rename("$base/Fotos", "$base/Umbenannt")
            assertEquals("notes", fs.openInput("$base/Umbenannt/trip/b.txt").use { String(it.readBytes()) })
            assertTrue(fs.exists("$base/Umbenannt/Ä b+c.jpg"))
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

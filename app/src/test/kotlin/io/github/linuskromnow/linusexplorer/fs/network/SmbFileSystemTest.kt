package io.github.linuskromnow.linusexplorer.fs.network

import io.github.linuskromnow.linusexplorer.fs.FileSystemRegistry
import io.github.linuskromnow.linusexplorer.fs.FileTransfer
import io.github.linuskromnow.linusexplorer.fs.InMemoryFileSystem
import io.github.linuskromnow.linusexplorer.fs.Location
import io.github.linuskromnow.linusexplorer.fs.NotFoundFsException
import io.github.linuskromnow.linusexplorer.fs.PasswordRequiredFsException
import io.github.linuskromnow.linusexplorer.fs.network.smb.SmbFileSystem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * Runs against a real SMB server, which can't be embedded in the JVM. Opt-in via environment:
 * SMB_TEST_HOST, SMB_TEST_PORT (default 445), SMB_TEST_SHARE, SMB_TEST_USER, SMB_TEST_PASSWORD.
 * The share must be writable; the test works in a fresh "linusexplorer-test-*" folder and removes it.
 */
class SmbFileSystemTest {
    private val host: String? = System.getenv("SMB_TEST_HOST")

    @Before
    fun requireServer() = assumeTrue("set SMB_TEST_HOST to run SMB tests", host != null)

    private fun smb(password: String? = System.getenv("SMB_TEST_PASSWORD")) = SmbFileSystem(
        Connection(
            id = "smb",
            name = "smb",
            protocol = Protocol.SMB,
            host = host!!,
            port = System.getenv("SMB_TEST_PORT")?.toInt() ?: 445,
            username = System.getenv("SMB_TEST_USER").orEmpty(),
            password = password,
            share = System.getenv("SMB_TEST_SHARE")!!,
        ),
    )

    @Test
    fun roundTrip() = runTest {
        val fs = smb()
        val base = "/linusexplorer-test-${System.currentTimeMillis()}"
        val phone = InMemoryFileSystem("phone").apply {
            write("/Photos/a.jpg", "jpeg bytes")
            write("/Photos/trip/b.txt", "notes")
        }
        val big = Random(3).nextBytes(3 * 1024 * 1024 + 11)
        phone.openOutput("/big.bin").use { it.write(big) }
        val back = InMemoryFileSystem("back")
        val transfer = FileTransfer(FileSystemRegistry().apply { register(phone); register(fs); register(back) })
        try {
            fs.createDirectory(base)
            transfer.run(listOf(Location("phone", "/Photos"), Location("phone", "/big.bin")), Location(fs.id, base), move = false) {}
            assertEquals(listOf("Photos", "big.bin"), fs.list(base).map { it.name }.sorted())
            assertTrue(fs.stat("$base/Photos").isDirectory)
            assertEquals(big.size.toLong(), fs.stat("$base/big.bin").size)
            transfer.run(listOf(Location(fs.id, "$base/big.bin")), Location("back", "/"), move = false) {}
            assertArrayEquals(big, back.openInput("/big.bin").readBytes())
            fs.rename("$base/Photos", "$base/Renamed")
            assertEquals("notes", fs.openInput("$base/Renamed/trip/b.txt").use { String(it.readBytes()) })
            try {
                fs.stat("$base/nope")
                fail("missing file found")
            } catch (e: NotFoundFsException) {
                // expected
            }
        } finally {
            runCatching { fs.delete(base) }
            fs.close()
        }
    }

    @Test
    fun wrongPasswordAsks() = runTest {
        assumeTrue(!System.getenv("SMB_TEST_USER").isNullOrBlank())
        try {
            smb(password = "definitely-wrong").list("/")
            fail("wrong password accepted")
        } catch (e: PasswordRequiredFsException) {
            assertTrue(e.wrongPassword)
        }
    }
}

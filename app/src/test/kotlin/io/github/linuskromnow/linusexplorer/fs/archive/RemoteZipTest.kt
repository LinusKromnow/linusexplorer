package io.github.linuskromnow.linusexplorer.fs.archive

import io.github.linuskromnow.linusexplorer.fs.InMemoryFileSystem
import io.github.linuskromnow.linusexplorer.fs.RangeReadable
import io.github.linuskromnow.linusexplorer.fs.RangeUnsupportedFsException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class RemoteZipTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** A "server" holding one file, counting how much of it is requested. */
    private class FakeServer(private val bytes: ByteArray) : RangeReadable {
        var requests = 0
        var bytesServed = 0L

        override suspend fun readRange(path: String, offset: Long, length: Int): ByteArray {
            requests++
            val end = minOf(bytes.size.toLong(), offset + length).toInt()
            return bytes.copyOfRange(offset.toInt(), end).also { bytesServed += it.size }
        }
    }

    /** 200 incompressible 50 KB files: about 10 MB that browsing must not download. */
    private fun bigZip(): Pair<ByteArray, Map<String, ByteArray>> {
        val random = Random(7)
        val files = (0 until 200).associate { "photos/IMG_${it.toString().padStart(4, '0')}.jpg" to random.nextBytes(50_000) }
        val zip = File(temp.root, "big.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("readme.txt"))
            out.write("hello".toByteArray())
            for ((name, data) in files) {
                out.putNextEntry(ZipEntry(name))
                out.write(data)
            }
        }
        return zip.readBytes() to files
    }

    private fun remote(server: FakeServer, size: Long) =
        ArchiveFileSystem("archive:net:x:/big.zip", ArchiveSource.Remote(server, "/big.zip", size, 0), ArchiveFormat.ZIP)

    @Test
    fun browsingAndOpeningOneFileFetchesLittle() = runTest {
        val (bytes, files) = bigZip()
        val server = FakeServer(bytes)
        val archive = remote(server, bytes.size.toLong())

        assertEquals(listOf("photos", "readme.txt"), archive.list("/").map { it.name }.sorted())
        assertEquals(200, archive.list("/photos").size)
        val afterListing = server.bytesServed
        assertTrue("listing fetched $afterListing of ${bytes.size} bytes", afterListing < bytes.size / 20)

        val name = "photos/IMG_0123.jpg"
        assertArrayEquals(files.getValue(name), archive.openInput("/$name").use { it.readBytes() })
        assertEquals("hello", archive.openInput("/readme.txt").use { String(it.readBytes()) })
        val total = server.bytesServed
        assertTrue("opening two files fetched $total of ${bytes.size} bytes", total < bytes.size / 10)

        // The table of contents is read once and the archive stays open: listing again costs nothing.
        val requests = server.requests
        archive.list("/photos")
        archive.stat("/readme.txt")
        assertEquals(requests, server.requests)
        archive.close()
    }

    @Test
    fun encryptedZipsOnServersAreRefused() = runTest {
        val source = InMemoryFileSystem("src").apply { write("/secret.txt", "psst") }
        val zip = File(temp.root, "locked.zip")
        ArchiveWriter.create(listOf(source to source.stat("/secret.txt")), zip, ArchiveFormat.ZIP, "pw".toCharArray()) {}
        val bytes = zip.readBytes()
        try {
            remote(FakeServer(bytes), bytes.size.toLong()).list("/")
            fail("encrypted ZIP was opened remotely")
        } catch (e: RangeUnsupportedFsException) {
            // expected: the app downloads it and opens it with the password instead
        }
    }

    @Test
    fun localZipsStayOpenAndReadEntriesDirectly() = runTest {
        val (bytes, files) = bigZip()
        val file = File(temp.root, "local.zip").apply { writeBytes(bytes) }
        val archive = ArchiveFileSystem("a", file, ArchiveFormat.ZIP)
        for ((name, data) in files.entries.shuffled(Random(1)).take(50)) {
            assertArrayEquals(data, archive.openInput("/$name").use { it.readBytes() })
        }
        assertTrue(archive.isCurrent(archive.stat("/readme.txt")))
        archive.close()
        // Closing only releases the file; the next use opens it again.
        assertEquals("hello", archive.openInput("/readme.txt").use { String(it.readBytes()) })
        archive.close()
    }

    @Test
    fun channelServesSeeksAcrossBlocks() {
        val data = Random(3).nextBytes(RangeChannel.BLOCK_SIZE * 3 + 123)
        val channel = RangeChannel(data.size.toLong()) { offset, length -> data.copyOfRange(offset.toInt(), (offset + length).toInt()) }
        val buffer = java.nio.ByteBuffer.allocate(1000)
        channel.position(RangeChannel.BLOCK_SIZE - 500L)
        assertEquals(1000, channel.read(buffer))
        assertArrayEquals(data.copyOfRange(RangeChannel.BLOCK_SIZE - 500, RangeChannel.BLOCK_SIZE + 500), buffer.array())
        channel.position(data.size - 10L)
        buffer.clear()
        assertEquals(10, channel.read(buffer))
        assertEquals(-1, channel.read(buffer.apply { clear() }))
        assertEquals(2L * RangeChannel.BLOCK_SIZE + 123, channel.bytesFetched) // blocks 0 and 1, and the 123-byte tail
    }
}

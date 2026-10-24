package io.github.linuskromnow.linusexplorer.fs.properties

import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.InMemoryFileSystem
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class PropertiesTest {
    private fun tree() = InMemoryFileSystem().apply {
        write("/a/one.txt", "12345")
        write("/a/sub/two.txt", "123")
        write("/a/sub/.hidden", "12")
        mkdirs("/a/empty")
        write("/b.txt", "1234567")
    }

    @Test
    fun measuresFolderRecursivelyIncludingHidden() = runTest {
        val fs = tree()
        val stats = FolderSizer.measure(fs, listOf(fs.stat("/a")))
        assertEquals(FolderStats(bytes = 10, files = 3, folders = 2), stats)
    }

    @Test
    fun mixesFilesAndFoldersAndReportsProgress() = runTest {
        val fs = tree()
        val reports = mutableListOf<FolderStats>()
        val stats = FolderSizer.measure(fs, listOf(fs.stat("/a"), fs.stat("/b.txt"))) { reports += it }
        assertEquals(FolderStats(bytes = 17, files = 4, folders = 2), stats)
        assertEquals(stats, reports.last())
    }

    @Test
    fun countsUnreadableFolders() = runTest {
        val fs = tree()
        val failing = object : VirtualFileSystem by fs {
            override suspend fun list(path: String): List<FileEntry> =
                if (path == "/a/sub") throw java.io.IOException("denied") else fs.list(path)
        }
        val stats = FolderSizer.measure(failing, listOf(fs.stat("/a")))
        assertEquals(1, stats.unreadable)
        assertEquals(5L, stats.bytes)
    }

    @Test
    fun sizingCanBeCancelled() = runTest {
        val fs = tree()
        var lists = 0
        val cancelling = object : VirtualFileSystem by fs {
            override suspend fun list(path: String): List<FileEntry> {
                if (++lists == 2) currentCoroutineContext().job.cancel()
                return fs.list(path)
            }
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { FolderSizer.measure(cancelling, listOf(fs.stat("/a"))) }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(2, lists)
    }

    @Test
    fun knownDigests() = runTest {
        val fs = InMemoryFileSystem().apply {
            write("/abc", "abc")
            write("/empty", "")
        }
        val abc = Checksums.compute(fs, "/abc")
        assertEquals("900150983cd24fb0d6963f7d28e17f72", abc[ChecksumAlgorithm.MD5])
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", abc[ChecksumAlgorithm.SHA1])
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", abc[ChecksumAlgorithm.SHA256])
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Checksums.compute(fs, "/empty", listOf(ChecksumAlgorithm.MD5))[ChecksumAlgorithm.MD5])
    }

    @Test
    fun streamsInChunksAndReportsProgress() = runTest {
        val big = ByteArray(300_000) { (it % 251).toByte() }
        val fs = object : VirtualFileSystem by InMemoryFileSystem() {
            override suspend fun openInput(path: String): InputStream = ByteArrayInputStream(big)
        }
        var last = 0L
        var calls = 0
        val result = Checksums.compute(fs, "/x", listOf(ChecksumAlgorithm.SHA256)) { last = it; calls++ }
        assertEquals(big.size.toLong(), last)
        assertTrue(calls > 1)
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(big).joinToString("") { "%02x".format(it) }
        assertEquals(expected, result[ChecksumAlgorithm.SHA256])
    }

    @Test
    fun hashingCanBeCancelled() = runTest {
        val fs = object : VirtualFileSystem by InMemoryFileSystem() {
            override suspend fun openInput(path: String): InputStream = ByteArrayInputStream(ByteArray(1_000_000))
        }
        var calls = 0
        val job = launch {
            Checksums.compute(fs, "/x") { calls++; if (calls == 2) throw kotlinx.coroutines.CancellationException("stop") }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(2, calls)
    }
}

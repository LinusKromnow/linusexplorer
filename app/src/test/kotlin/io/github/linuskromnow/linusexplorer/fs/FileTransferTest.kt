package io.github.linuskromnow.linusexplorer.fs

import io.github.linuskromnow.linusexplorer.fs.local.LocalFileSystem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileTransferTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val local = LocalFileSystem()
    private val transfer = FileTransfer(FileSystemRegistry().apply { register(local) })

    private fun loc(file: File) = Location(LocalFileSystem.ID, file.path)

    @Before
    fun requirePosixPaths() {
        // VFS paths are "/"-separated like on Android; Windows temp dirs aren't. CI runs these on Linux.
        assumeTrue(File.separatorChar == '/')
    }

    @Test
    fun numberedNames() {
        assertEquals("photo (1).jpg", FileTransfer.numberedName("photo.jpg", 1, isDirectory = false))
        assertEquals("archive.tar (2).gz", FileTransfer.numberedName("archive.tar.gz", 2, isDirectory = false))
        assertEquals(".nomedia (1)", FileTransfer.numberedName(".nomedia", 1, isDirectory = false))
        assertEquals("my.folder (1)", FileTransfer.numberedName("my.folder", 1, isDirectory = true))
    }

    @Test
    fun copiesTreeAndReportsProgress() = runTest {
        val src = temp.newFolder("src")
        File(src, "a.txt").writeText("hello")
        File(src, "sub").mkdir()
        File(src, "sub/b.txt").writeText("world!")
        val dest = temp.newFolder("dest")

        var last: TransferProgress? = null
        val count = transfer.run(listOf(loc(src)), loc(dest), move = false) { last = it }

        assertEquals(1, count)
        assertEquals("hello", File(dest, "src/a.txt").readText())
        assertEquals("world!", File(dest, "src/sub/b.txt").readText())
        assertTrue(src.exists())
        assertEquals(2, last?.filesDone)
        assertEquals(11L, last?.bytesDone)
    }

    @Test
    fun copyIntoSameFolderRenames() = runTest {
        val file = temp.newFile("note.txt").apply { writeText("x") }
        transfer.run(listOf(loc(file)), loc(temp.root), move = false) {}
        assertEquals("x", File(temp.root, "note (1).txt").readText())
    }

    @Test
    fun moveRemovesSource() = runTest {
        val file = temp.newFile("move.txt").apply { writeText("data") }
        val dest = temp.newFolder("target")
        transfer.run(listOf(loc(file)), loc(dest), move = true) {}
        assertFalse(file.exists())
        assertEquals("data", File(dest, "move.txt").readText())
    }

    @Test(expected = FsException::class)
    fun refusesCopyIntoItself() = runTest {
        val dir = temp.newFolder("loop")
        val inner = File(dir, "inner").apply { mkdir() }
        transfer.run(listOf(loc(dir)), loc(inner), move = false) {}
    }

    @Test
    fun deleteRemovesTree() = runTest {
        val dir = temp.newFolder("gone")
        File(dir, "nested").mkdir()
        File(dir, "nested/file").writeText("1")
        local.delete(dir.path)
        assertFalse(dir.exists())
    }
}

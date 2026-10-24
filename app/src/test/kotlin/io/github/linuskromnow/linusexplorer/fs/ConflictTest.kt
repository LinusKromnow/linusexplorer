package io.github.linuskromnow.linusexplorer.fs

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictTest {
    private val src = InMemoryFileSystem("src").apply {
        write("/docs/a.txt", "new a")
        write("/docs/b.txt", "new b")
        write("/single.txt", "new single")
    }
    private val dst = InMemoryFileSystem("dst").apply {
        write("/docs/a.txt", "old a")
        write("/docs/keep.txt", "untouched")
        write("/single.txt", "old single")
    }
    private val transfer = FileTransfer(FileSystemRegistry().apply { register(src); register(dst) })

    private suspend fun copy(vararg paths: String, move: Boolean = false, resolver: ConflictResolver): Int =
        transfer.run(paths.map { Location("src", it) }, Location("dst", "/"), move, resolver) {}

    @Test
    fun replaceFileAndMergeFolders() = runTest {
        val asked = mutableListOf<String>()
        copy("/docs", "/single.txt") { source, _ -> asked += source.name; ConflictResolution.REPLACE }
        assertEquals(listOf("docs", "single.txt", "a.txt"), asked)
        assertEquals("new a", dst.read("/docs/a.txt"))
        assertEquals("new b", dst.read("/docs/b.txt"))
        assertEquals("untouched", dst.read("/docs/keep.txt")) // merged, not wiped
        assertEquals("new single", dst.read("/single.txt"))
        assertFalse(dst.allPaths().any { it.endsWith(".part") })
    }

    @Test
    fun skipLeavesTargetAlone() = runTest {
        val count = copy("/single.txt") { _, _ -> ConflictResolution.SKIP }
        assertEquals(0, count)
        assertEquals("old single", dst.read("/single.txt"))
    }

    @Test
    fun keepBoth() = runTest {
        copy("/single.txt") { _, _ -> ConflictResolution.KEEP_BOTH }
        assertEquals("old single", dst.read("/single.txt"))
        assertEquals("new single", dst.read("/single (1).txt"))
    }

    @Test
    fun movePartiallySkippedFolderKeepsSource() = runTest {
        copy("/docs", move = true) { source, _ ->
            if (source.isDirectory) ConflictResolution.REPLACE else ConflictResolution.SKIP
        }
        assertEquals("old a", dst.read("/docs/a.txt"))
        assertEquals("new b", dst.read("/docs/b.txt"))
        assertTrue(src.exists("/docs/a.txt")) // a.txt was skipped, so the source folder stays
    }

    @Test
    fun duplicateInSameFolderNeverAsks() = runTest {
        val neverAsk = ConflictResolver { _, _ -> error("should not ask") }
        transfer.run(listOf(Location("dst", "/single.txt")), Location("dst", "/"), move = false, neverAsk) {}
        assertEquals("old single", dst.read("/single (1).txt"))
    }
}

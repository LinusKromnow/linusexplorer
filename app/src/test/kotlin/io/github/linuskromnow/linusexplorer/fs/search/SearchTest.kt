package io.github.linuskromnow.linusexplorer.fs.search

import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.InMemoryFileSystem
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private fun matches(query: String, name: String) = NameMatcher.parse(query)!!.matches(name)

    @Test
    fun plainQueryIsCaseInsensitiveSubstring() {
        assertTrue(matches("report", "Annual REPORT 2024.pdf"))
        assertTrue(matches("  .PDF ", "a.pdf"))
        assertFalse(matches("report", "rapport.pdf"))
        assertNull(NameMatcher.parse("   "))
    }

    @Test
    fun wildcardsMatchWholeName() {
        assertTrue(matches("*.jpg", "cat.JPG"))
        assertFalse(matches("*.jpg", "cat.jpg.bak"))
        assertTrue(matches("img_??.png", "img_07.png"))
        assertFalse(matches("img_??.png", "img_7.png"))
        assertTrue(matches("a*b*c", "aXXbYYc"))
        assertFalse(matches("a*b*c", "aXXbYY"))
        assertTrue(matches("*", "anything"))
        assertTrue(matches("a**", "a"))
        assertTrue(matches("*abc", "ababc"))
    }

    private fun tree() = InMemoryFileSystem().apply {
        write("/root/a.txt", "1")
        write("/root/docs/note.txt", "22")
        write("/root/docs/.hidden.txt", "3")
        write("/root/docs/deep/er/notes.md", "4")
        mkdirs("/root/notes-dir")
    }

    private suspend fun run(
        fs: VirtualFileSystem,
        query: String,
        limits: SearchLimits = SearchLimits(),
    ): Pair<List<SearchHit>, SearchSummary> {
        val hits = mutableListOf<SearchHit>()
        val summary = FileSearch.search(fs, "/root", NameMatcher.parse(query)!!, limits) { hits += it }
        return hits to summary
    }

    @Test
    fun findsRecursivelyShallowFirstWithRelativePaths() = runTest {
        val (hits, summary) = run(tree(), "note")
        assertEquals(listOf("notes-dir", "docs/note.txt", "docs/deep/er/notes.md"), hits.map { it.relativePath })
        assertFalse(summary.truncated)
        assertTrue(hits[0].entry.isDirectory)
    }

    @Test
    fun hiddenFilesFollowTheOption() = runTest {
        assertTrue(run(tree(), "hidden").first.isEmpty())
        assertEquals(1, run(tree(), "hidden", SearchLimits(includeHidden = true)).first.size)
    }

    @Test
    fun capsResultsAndDepth() = runTest {
        val (hits, summary) = run(tree(), "t", SearchLimits(maxResults = 2))
        assertEquals(2, hits.size)
        assertTrue(summary.truncated)
        assertEquals(listOf("notes-dir", "docs/note.txt"), run(tree(), "note", SearchLimits(maxDepth = 2)).first.map { it.relativePath })
    }

    /** Reports a directory named "loop" as a symbolic link to its own parent. */
    private class LinkingFs(private val inner: InMemoryFileSystem) : VirtualFileSystem by inner {
        override suspend fun list(path: String): List<FileEntry> =
            inner.list(path).map { if (it.name == "loop") it.copy(isSymlink = true) else it }
    }

    @Test
    fun doesNotEnterSymlinkedDirectories() = runTest {
        val fs = InMemoryFileSystem().apply {
            write("/root/loop/inside.txt", "x")
            write("/root/x.txt", "x")
        }
        val (hits, summary) = run(LinkingFs(fs), "*")
        assertEquals(setOf("loop", "x.txt"), hits.map { it.relativePath }.toSet())
        assertEquals(2, summary.scanned)
    }

    @Test
    fun unreadableSubfoldersAreSkippedButRootErrorsPropagate() = runTest {
        val fs = tree()
        val failing = object : VirtualFileSystem by fs {
            override suspend fun list(path: String): List<FileEntry> =
                if (path == "/root/docs") throw java.io.IOException("denied") else fs.list(path)
        }
        val (hits, summary) = run(failing, "txt")
        assertEquals(listOf("a.txt"), hits.map { it.relativePath })
        assertEquals(1, summary.skippedDirectories)
        var thrown = false
        try {
            FileSearch.search(failing, "/missing", NameMatcher.parse("x")!!) {}
        } catch (e: Exception) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun cancellationStopsTheWalk() = runTest {
        val fs = tree()
        var seen = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            FileSearch.search(fs, "/root", NameMatcher.parse("*")!!) {
                seen++
                currentCoroutineContext().job.cancel()
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, seen)
    }
}

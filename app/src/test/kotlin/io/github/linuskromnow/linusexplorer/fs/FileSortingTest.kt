package io.github.linuskromnow.linusexplorer.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSortingTest {
    private fun file(name: String, size: Long = 0, modified: Long = 0) =
        FileEntry(name, "/$name", isDirectory = false, size = size, lastModified = modified)

    private fun dir(name: String) = FileEntry(name, "/$name", isDirectory = true, size = 0, lastModified = 0)

    @Test
    fun naturalOrder() {
        val sorted = listOf("file10", "File2", "file1", "file02b").sortedWith(NaturalOrderComparator)
        assertEquals(listOf("file1", "File2", "file02b", "file10"), sorted)
    }

    @Test
    fun naturalOrderIsConsistentForEqualIgnoringCase() {
        assertTrue(NaturalOrderComparator.compare("a", "A") != 0)
    }

    @Test
    fun foldersFirstAndHiddenFiltered() {
        val entries = listOf(file("b.txt"), dir("zeta"), file(".hidden"), dir("alpha"), file("a.txt"))
        val names = FileSorting.sort(entries, BrowserPrefs()).map { it.name }
        assertEquals(listOf("alpha", "zeta", "a.txt", "b.txt"), names)
    }

    @Test
    fun showHiddenAndSortBySizeDescending() {
        val entries = listOf(file("small", size = 1), file(".big", size = 100), file("mid", size = 10))
        val prefs = BrowserPrefs(sortField = SortField.SIZE, descending = true, showHidden = true)
        assertEquals(listOf(".big", "mid", "small"), FileSorting.sort(entries, prefs).map { it.name })
    }

    @Test
    fun descendingKeepsFoldersFirst() {
        val entries = listOf(file("a"), dir("b"), file("c"))
        val prefs = BrowserPrefs(descending = true)
        assertEquals(listOf("b", "c", "a"), FileSorting.sort(entries, prefs).map { it.name })
    }
}

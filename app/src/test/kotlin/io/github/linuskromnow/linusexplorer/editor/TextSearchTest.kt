package io.github.linuskromnow.linusexplorer.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {
    private val text = "Foo bar foo BAR FOO"

    @Test
    fun findNextWrapsAround() {
        assertEquals(Match(0, 3), TextSearch.findNext(text, "foo", 0, caseSensitive = false))
        assertEquals(Match(8, 11), TextSearch.findNext(text, "foo", 1, caseSensitive = false))
        assertEquals(Match(0, 3), TextSearch.findNext(text, "foo", 17, caseSensitive = false))
    }

    @Test
    fun findPreviousWrapsAround() {
        assertEquals(Match(8, 11), TextSearch.findPrevious(text, "foo", 16, caseSensitive = false))
        assertEquals(Match(16, 19), TextSearch.findPrevious(text, "foo", 0, caseSensitive = false))
        // A match that ends exactly at the boundary counts.
        assertEquals(Match(8, 11), TextSearch.findPrevious(text, "foo", 11, caseSensitive = false))
    }

    @Test
    fun caseSensitivity() {
        assertEquals(Match(8, 11), TextSearch.findNext(text, "foo", 1, caseSensitive = true))
        assertEquals(Match(16, 19), TextSearch.findNext(text, "FOO", 0, caseSensitive = true))
        assertNull(TextSearch.findNext(text, "fOo", 0, caseSensitive = true))
        assertEquals(3, TextSearch.count(text, "foo", caseSensitive = false))
        assertEquals(1, TextSearch.count(text, "foo", caseSensitive = true))
    }

    @Test
    fun emptyQueryAndNoMatch() {
        assertNull(TextSearch.findNext(text, "", 0, true))
        assertNull(TextSearch.findPrevious(text, "", 0, true))
        assertNull(TextSearch.findNext(text, "zzz", 0, true))
        assertEquals(0, TextSearch.count(text, "", true))
        assertEquals(ReplaceResult(text, 0), TextSearch.replaceAll(text, "", "x", true))
    }

    @Test
    fun replaceAllIsCaseAware() {
        assertEquals(ReplaceResult("x bar x BAR x", 3), TextSearch.replaceAll(text, "foo", "x", caseSensitive = false))
        assertEquals(ReplaceResult("Foo bar x BAR FOO", 1), TextSearch.replaceAll(text, "foo", "x", caseSensitive = true))
    }

    @Test
    fun replaceAllDoesNotRescanReplacement() {
        assertEquals(ReplaceResult("abab", 2), TextSearch.replaceAll("aa", "a", "ab", true))
        assertEquals(ReplaceResult("", 3), TextSearch.replaceAll("aaa", "a", "", true))
    }

    @Test
    fun matchesAt() {
        assertTrue(TextSearch.matchesAt(text, "foo", Match(8, 11), caseSensitive = true))
        assertFalse(TextSearch.matchesAt(text, "foo", Match(0, 3), caseSensitive = true))
        assertTrue(TextSearch.matchesAt(text, "foo", Match(0, 3), caseSensitive = false))
        assertFalse(TextSearch.matchesAt(text, "foo", Match(8, 10), caseSensitive = false))
    }
}

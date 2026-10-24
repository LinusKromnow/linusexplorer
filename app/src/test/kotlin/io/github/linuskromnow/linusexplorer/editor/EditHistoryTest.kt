package io.github.linuskromnow.linusexplorer.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {
    @Test
    fun undoAndRedoSingleEdit() {
        val history = EditHistory()
        history.record("hello world", "hello brave world")
        assertTrue(history.canUndo)
        val undone = history.undo("hello brave world")!!
        assertEquals("hello world", undone.text)
        assertEquals(6, undone.cursor)
        val redone = history.redo(undone.text)!!
        assertEquals("hello brave world", redone.text)
        assertEquals(12, redone.cursor)
    }

    @Test
    fun typingIsMergedIntoOneStep() {
        val history = EditHistory()
        history.record("", "a")
        history.record("a", "ab")
        history.record("ab", "abc")
        assertEquals("", history.undo("abc")!!.text)
        assertFalse(history.canUndo)
    }

    @Test
    fun backspacingIsMerged() {
        val history = EditHistory()
        history.record("abc", "ab")
        history.record("ab", "a")
        assertEquals("abc", history.undo("a")!!.text)
        assertFalse(history.canUndo)
    }

    @Test
    fun newlineStartsANewStep() {
        val history = EditHistory()
        history.record("", "a")
        history.record("a", "a\n")
        assertEquals("a", history.undo("a\n")!!.text)
        assertEquals("", history.undo("a")!!.text)
    }

    @Test
    fun newEditClearsRedo() {
        val history = EditHistory()
        history.record("a", "ab")
        val undone = history.undo("ab")!!
        history.record(undone.text, "ax")
        assertFalse(history.canRedo)
        assertNull(history.redo("ax"))
    }

    @Test
    fun undoAfterUndoDoesNotMerge() {
        val history = EditHistory()
        history.record("", "a")
        history.undo("a")
        history.record("", "b")
        history.record("b", "bc")
        assertEquals("", history.undo("bc")!!.text)
    }

    @Test
    fun replacementDiffOnlyTouchesTheChangedRegion() {
        val history = EditHistory()
        history.record("one two three", "one 2 three")
        assertEquals("one two three", history.undo("one 2 three")!!.text)
    }

    @Test
    fun repeatedCharactersDiffCorrectly() {
        val history = EditHistory()
        history.record("aaa", "aaaa")
        assertEquals("aaa", history.undo("aaaa")!!.text)
    }

    @Test
    fun stackIsBounded() {
        val history = EditHistory(maxEntries = 3)
        var text = ""
        repeat(10) {
            val next = text + "x\n" // newline keeps the steps from merging
            history.record(text, next)
            text = next
        }
        var steps = 0
        while (true) {
            text = history.undo(text)?.text ?: break
            steps++
        }
        assertEquals(3, steps)
    }
}

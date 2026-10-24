package io.github.linuskromnow.linusexplorer.editor

/** Result of an undo or redo: the new [text] and where the cursor belongs. */
data class HistoryStep(val text: String, val cursor: Int)

/**
 * Undo/redo stack storing only the changed region of each edit, so it stays small for big files.
 * Consecutive typing (or backspacing) is merged into one step.
 */
class EditHistory(private val maxEntries: Int = 500) {
    private class Edit(val offset: Int, val removed: String, val inserted: String)

    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    /** Set after a merge-able edit; cleared by undo/redo and by edits that must start a new step. */
    private var canMerge = false

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        canMerge = false
    }

    /** Records that the text changed from [before] to [after]; does nothing if they are equal. */
    fun record(before: String, after: String) {
        if (before == after) return
        var prefix = 0
        val maxPrefix = minOf(before.length, after.length)
        while (prefix < maxPrefix && before[prefix] == after[prefix]) prefix++
        var suffix = 0
        val maxSuffix = maxPrefix - prefix
        while (suffix < maxSuffix && before[before.length - 1 - suffix] == after[after.length - 1 - suffix]) suffix++
        val edit = Edit(
            offset = prefix,
            removed = before.substring(prefix, before.length - suffix),
            inserted = after.substring(prefix, after.length - suffix),
        )
        redoStack.clear()
        val last = undoStack.lastOrNull()
        val typing = edit.removed.isEmpty() && edit.inserted.length == 1 && edit.inserted != "\n"
        val backspace = edit.inserted.isEmpty() && edit.removed.length == 1 && edit.removed != "\n"
        if (canMerge && last != null) {
            if (typing && last.removed.isEmpty() && last.offset + last.inserted.length == edit.offset) {
                undoStack[undoStack.lastIndex] = Edit(last.offset, "", last.inserted + edit.inserted)
                return
            }
            if (backspace && last.inserted.isEmpty() && edit.offset + 1 == last.offset) {
                undoStack[undoStack.lastIndex] = Edit(edit.offset, edit.removed + last.removed, "")
                return
            }
        }
        undoStack.addLast(edit)
        if (undoStack.size > maxEntries) undoStack.removeFirst()
        canMerge = typing || backspace
    }

    /** Reverts the latest edit of [current]; null if there is nothing to undo. */
    fun undo(current: String): HistoryStep? {
        val edit = undoStack.removeLastOrNull() ?: return null
        canMerge = false
        redoStack.addLast(edit)
        return HistoryStep(
            current.substring(0, edit.offset) + edit.removed + current.substring(edit.offset + edit.inserted.length),
            edit.offset + edit.removed.length,
        )
    }

    /** Re-applies the most recently undone edit; null if there is nothing to redo. */
    fun redo(current: String): HistoryStep? {
        val edit = redoStack.removeLastOrNull() ?: return null
        canMerge = false
        undoStack.addLast(edit)
        return HistoryStep(
            current.substring(0, edit.offset) + edit.inserted + current.substring(edit.offset + edit.removed.length),
            edit.offset + edit.inserted.length,
        )
    }
}

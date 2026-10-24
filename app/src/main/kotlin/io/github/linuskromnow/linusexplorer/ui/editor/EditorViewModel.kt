package io.github.linuskromnow.linusexplorer.ui.editor

import android.app.Application
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.linuskromnow.linusexplorer.LinusExplorerApp
import io.github.linuskromnow.linusexplorer.R
import io.github.linuskromnow.linusexplorer.editor.EditHistory
import io.github.linuskromnow.linusexplorer.editor.EditorPrefs
import io.github.linuskromnow.linusexplorer.editor.HistoryStep
import io.github.linuskromnow.linusexplorer.editor.LineEnding
import io.github.linuskromnow.linusexplorer.editor.LoadResult
import io.github.linuskromnow.linusexplorer.editor.Match
import io.github.linuskromnow.linusexplorer.editor.TextDocument
import io.github.linuskromnow.linusexplorer.editor.TextEncoding
import io.github.linuskromnow.linusexplorer.editor.TextFiles
import io.github.linuskromnow.linusexplorer.editor.TextSearch
import io.github.linuskromnow.linusexplorer.fs.Location
import io.github.linuskromnow.linusexplorer.fs.VfsPaths
import io.github.linuskromnow.linusexplorer.ui.browser.BrowserEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FindState(
    val query: String = "",
    val replacement: String = "",
    val caseSensitive: Boolean = false,
    val matchCount: Int = 0,
    /** The match shown highlighted, or null after the text was edited. */
    val current: Match? = null,
)

data class EditorUiState(
    val location: Location? = null,
    val name: String = "",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    /** Why the file can't be shown at all (binary, missing, ...). */
    val error: String? = null,
    val value: TextFieldValue = TextFieldValue(),
    val savedText: String = "",
    val document: TextDocument? = null,
    /** The user's read-only switch; ignored when [forcedReadOnly]. */
    val readOnlyToggle: Boolean = false,
    /** Read-only storage or a truncated big file: editing is impossible. */
    val forcedReadOnly: Boolean = false,
    val notice: Int? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val prefs: EditorPrefs = EditorPrefs(),
    val find: FindState? = null,
    /** Bumped whenever the view should scroll to the cursor (find results). */
    val revealTick: Int = 0,
) {
    val readOnly: Boolean get() = forcedReadOnly || readOnlyToggle
    val dirty: Boolean get() = document != null && value.text != savedText
}

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as LinusExplorerApp).container
    private val fileSystems = container.fileSystems
    private val preferences = container.preferences

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    // Reuses the browser's message type so the screen can resolve strings the same way.
    private val _events = Channel<BrowserEvent.Message>(Channel.BUFFERED)
    val events: Flow<BrowserEvent.Message> = _events.receiveAsFlow()

    private val history = EditHistory()

    init {
        viewModelScope.launch {
            preferences.editor.collect { prefs -> _state.update { it.copy(prefs = prefs) } }
        }
    }

    /** Loads [location] unless it is already open (e.g. after a configuration change). */
    fun open(location: Location) {
        if (_state.value.location == location) return
        history.clear()
        _state.update { EditorUiState(location = location, name = VfsPaths.name(location.path), isLoading = true, prefs = it.prefs) }
        viewModelScope.launch {
            try {
                val fs = fileSystems[location.fsId]
                when (val result = TextFiles.load(fs, location.path)) {
                    LoadResult.Binary -> fail(getApplication<Application>().getString(R.string.editor_binary))
                    is LoadResult.Text -> {
                        val doc = result.document
                        _state.update {
                            it.copy(
                                isLoading = false,
                                value = TextFieldValue(doc.text),
                                savedText = doc.text,
                                document = doc,
                                forcedReadOnly = doc.truncated || fs.isReadOnly,
                                notice = when {
                                    doc.truncated -> R.string.editor_notice_too_large
                                    fs.isReadOnly -> R.string.editor_notice_read_only_storage
                                    else -> null
                                },
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: e.toString())
            }
        }
    }

    /** Forgets the open file so the next [open] starts fresh. */
    fun close() {
        history.clear()
        _state.update { EditorUiState(prefs = it.prefs) }
    }

    private fun fail(message: String) {
        _state.update { it.copy(isLoading = false, error = message) }
    }

    // region Editing

    fun onValueChange(new: TextFieldValue) {
        val old = _state.value.value
        val text = LineEnding.normalize(new.text) // pasted text may bring \r\n
        if (_state.value.readOnly && text != old.text) return
        if (text == old.text) {
            _state.update { it.copy(value = new.copy(text = text)) }
            return
        }
        history.record(old.text, text)
        _state.update {
            it.copy(
                value = new.copy(text = text),
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                find = it.find?.copy(current = null, matchCount = recount(it.find, text)),
            )
        }
    }

    fun undo() = applyHistory { history.undo(it) }

    fun redo() = applyHistory { history.redo(it) }

    private fun applyHistory(step: (String) -> HistoryStep?) {
        if (_state.value.readOnly) return
        val result = step(_state.value.value.text) ?: return
        _state.update {
            it.copy(
                value = TextFieldValue(result.text, TextRange(result.cursor)),
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                find = it.find?.copy(current = null, matchCount = recount(it.find, result.text)),
                revealTick = it.revealTick + 1,
            )
        }
    }

    fun setReadOnly(readOnly: Boolean) {
        _state.update { it.copy(readOnlyToggle = readOnly) }
    }

    // endregion

    // region Saving

    /** Saves the file; [onSaved] runs on success (used by "Save and leave"). */
    fun save(onSaved: () -> Unit = {}) {
        val state = _state.value
        val location = state.location ?: return
        val document = state.document ?: return
        if (state.readOnly || state.isSaving) return
        val text = state.value.text
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                val saved = TextFiles.save(fileSystems[location.fsId], location.path, text, document)
                _state.update { it.copy(document = saved, savedText = text) }
                val upgraded = saved.encoding == TextEncoding.UTF8 && document.encoding != TextEncoding.UTF8
                _events.trySend(BrowserEvent.Message(if (upgraded) R.string.editor_saved_as_utf8 else R.string.editor_saved))
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.trySend(BrowserEvent.Message(R.string.msg_error, listOf(e.message ?: e.javaClass.simpleName)))
            } finally {
                _state.update { it.copy(isSaving = false) }
            }
        }
    }

    // endregion

    // region Preferences

    fun toggleWordWrap() {
        viewModelScope.launch { preferences.updateEditor { it.copy(wordWrap = !it.wordWrap) } }
    }

    fun changeFontSize(delta: Int) {
        viewModelScope.launch {
            preferences.updateEditor {
                it.copy(fontSizeSp = (it.fontSizeSp + delta).coerceIn(EditorPrefs.MIN_FONT_SIZE, EditorPrefs.MAX_FONT_SIZE))
            }
        }
    }

    // endregion

    // region Find & replace

    fun showFind() {
        _state.update { it.copy(find = it.find ?: FindState()) }
    }

    fun hideFind() {
        _state.update { it.copy(find = null) }
    }

    fun setQuery(query: String) {
        _state.update {
            val find = (it.find ?: FindState()).copy(query = query, current = null)
            it.copy(find = find.copy(matchCount = recount(find, it.value.text)))
        }
    }

    fun setReplacement(replacement: String) {
        _state.update { it.copy(find = it.find?.copy(replacement = replacement)) }
    }

    fun setCaseSensitive(caseSensitive: Boolean) {
        _state.update {
            val find = (it.find ?: FindState()).copy(caseSensitive = caseSensitive, current = null)
            it.copy(find = find.copy(matchCount = recount(find, it.value.text)))
        }
    }

    fun findNext() = jumpTo { text, find, selection ->
        TextSearch.findNext(text, find.query, selection.max, find.caseSensitive)
    }

    fun findPrevious() = jumpTo { text, find, selection ->
        TextSearch.findPrevious(text, find.query, selection.min, find.caseSensitive)
    }

    private fun jumpTo(search: (String, FindState, TextRange) -> Match?) {
        val state = _state.value
        val find = state.find ?: return
        val match = search(state.value.text, find, state.value.selection)
        _state.update {
            if (match == null) {
                it.copy(find = find.copy(current = null))
            } else {
                it.copy(
                    value = it.value.copy(selection = TextRange(match.start, match.end)),
                    find = find.copy(current = match),
                    revealTick = it.revealTick + 1,
                )
            }
        }
    }

    /** Replaces the highlighted match (if the selection is one), then moves on to the next. */
    fun replaceCurrent() {
        val state = _state.value
        val find = state.find ?: return
        if (state.readOnly) return
        val selection = Match(state.value.selection.min, state.value.selection.max)
        if (!TextSearch.matchesAt(state.value.text, find.query, selection, find.caseSensitive)) return findNext()
        val old = state.value.text
        val text = old.substring(0, selection.start) + find.replacement + old.substring(selection.end)
        history.record(old, text)
        val cursor = selection.start + find.replacement.length
        _state.update {
            it.copy(
                value = TextFieldValue(text, TextRange(cursor)),
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                find = find.copy(current = null, matchCount = recount(find, text)),
            )
        }
        findNext()
    }

    fun replaceAll() {
        val state = _state.value
        val find = state.find ?: return
        if (state.readOnly) return
        val old = state.value.text
        val result = TextSearch.replaceAll(old, find.query, find.replacement, find.caseSensitive)
        if (result.count == 0) return
        history.record(old, result.text)
        _state.update {
            it.copy(
                value = TextFieldValue(result.text, TextRange(0)),
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                find = find.copy(current = null, matchCount = recount(find, result.text)),
            )
        }
        _events.trySend(BrowserEvent.Message(R.plurals.editor_replaced, listOf(result.count), result.count))
    }

    private fun recount(find: FindState?, text: String): Int =
        if (find == null) 0 else TextSearch.count(text, find.query, find.caseSensitive)

    // endregion
}

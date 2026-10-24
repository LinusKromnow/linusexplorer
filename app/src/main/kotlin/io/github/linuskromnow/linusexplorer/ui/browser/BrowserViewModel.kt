package io.github.linuskromnow.linusexplorer.ui.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.linuskromnow.linusexplorer.LinusExplorerApp
import io.github.linuskromnow.linusexplorer.R
import io.github.linuskromnow.linusexplorer.fs.BrowserPrefs
import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.FileSorting
import io.github.linuskromnow.linusexplorer.fs.Location
import io.github.linuskromnow.linusexplorer.fs.VfsPaths
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import io.github.linuskromnow.linusexplorer.fs.local.LocalFileSystem
import io.github.linuskromnow.linusexplorer.storage.Place
import io.github.linuskromnow.linusexplorer.storage.Places
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as LinusExplorerApp).container
    private val fileSystems = container.fileSystems
    private val fileTransfer = container.fileTransfer
    private val preferences = container.preferences

    private val _state = MutableStateFlow(
        BrowserUiState(location = Location(LocalFileSystem.ID, Places.primaryStoragePath())),
    )
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private val _events = Channel<BrowserEvent>(Channel.BUFFERED)
    val events: Flow<BrowserEvent> = _events.receiveAsFlow()

    private val history = ArrayDeque<Location>()
    private val scrollPositions = HashMap<Location, Pair<Int, Int>>()
    private var unsortedEntries: List<FileEntry> = emptyList()
    private var loadJob: Job? = null
    private var transferJob: Job? = null

    init {
        refreshPlaces()
        viewModelScope.launch {
            preferences.browser.collect { prefs ->
                _state.update { it.copy(prefs = prefs, entries = FileSorting.sort(unsortedEntries, prefs)) }
            }
        }
        load()
    }

    // region Navigation

    fun navigateTo(path: String) {
        val current = _state.value.location
        val target = current.copy(path = VfsPaths.normalize(path))
        if (target == current) return refresh()
        history.addLast(current)
        if (history.size > MAX_HISTORY) history.removeFirst()
        show(target)
    }

    /** Returns false when there is nowhere to go back to. */
    fun navigateBack(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        show(previous)
        return true
    }

    fun refresh() = load()

    fun refreshPlaces() {
        val places = Places.all(getApplication())
        _state.update { it.copy(places = places, breadcrumbs = breadcrumbsFor(it.location.path, places)) }
    }

    fun scrollPosition(location: Location): Pair<Int, Int> = scrollPositions[location] ?: (0 to 0)

    fun saveScrollPosition(location: Location, index: Int, offset: Int) {
        scrollPositions[location] = index to offset
    }

    private fun show(location: Location) {
        unsortedEntries = emptyList()
        _state.update {
            it.copy(
                location = location,
                entries = emptyList(),
                selection = emptySet(),
                error = null,
                canGoBack = history.isNotEmpty(),
                breadcrumbs = breadcrumbsFor(location.path, it.places),
            )
        }
        load()
    }

    private fun load() {
        loadJob?.cancel()
        val location = _state.value.location
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val entries = fileSystems[location.fsId].list(location.path)
                unsortedEntries = entries
                val paths = entries.mapTo(HashSet()) { it.path }
                _state.update {
                    it.copy(
                        entries = FileSorting.sort(entries, it.prefs),
                        selection = it.selection.filterTo(HashSet()) { path -> path in paths },
                        isLoading = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unsortedEntries = emptyList()
                _state.update { it.copy(entries = emptyList(), isLoading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    private fun breadcrumbsFor(path: String, places: List<Place>): List<Breadcrumb> {
        val volume = places
            .filter { it.isVolume && VfsPaths.isAncestorOrSelf(it.path, path) }
            .maxByOrNull { it.path.length }
        val base = volume?.let { Breadcrumb(it.label, it.path) }
            ?: Breadcrumb(getApplication<Application>().getString(R.string.place_root), VfsPaths.ROOT)
        var current = base.path
        return listOf(base) + VfsPaths.relativeSegments(base.path, path).map { segment ->
            current = VfsPaths.child(current, segment)
            Breadcrumb(segment, current)
        }
    }

    // endregion

    // region Selection & clicks

    fun onEntryClick(entry: FileEntry) {
        when {
            _state.value.inSelectionMode -> toggleSelection(entry)
            entry.isDirectory -> navigateTo(entry.path)
            _state.value.location.fsId == LocalFileSystem.ID -> _events.trySend(BrowserEvent.OpenFile(entry.path))
            else -> _events.trySend(BrowserEvent.Message(R.string.msg_open_remote_unsupported))
        }
    }

    fun onEntryLongClick(entry: FileEntry) = toggleSelection(entry)

    fun toggleSelection(entry: FileEntry) {
        _state.update {
            it.copy(selection = if (entry.path in it.selection) it.selection - entry.path else it.selection + entry.path)
        }
    }

    fun selectAll() {
        _state.update { state -> state.copy(selection = state.entries.mapTo(HashSet()) { it.path }) }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet()) }
    }

    // endregion

    // region Clipboard & transfers

    fun copySelection(cut: Boolean) {
        val state = _state.value
        val items = state.selectedEntries.map { Location(state.location.fsId, it.path) }
        if (items.isEmpty()) return
        _state.update { it.copy(clipboard = Clipboard(items, cut), selection = emptySet()) }
        val message = if (cut) R.plurals.msg_cut_to_clipboard else R.plurals.msg_copied_to_clipboard
        _events.trySend(BrowserEvent.Message(message, listOf(items.size), items.size))
    }

    fun clearClipboard() {
        _state.update { it.copy(clipboard = null) }
    }

    fun paste() {
        val clipboard = _state.value.clipboard ?: return
        if (transferJob?.isActive == true) return
        val target = _state.value.location
        transferJob = viewModelScope.launch {
            _state.update { it.copy(transfer = TransferState(isMove = clipboard.isCut)) }
            try {
                val count = fileTransfer.run(clipboard.items, target, clipboard.isCut) { progress ->
                    _state.update { it.copy(transfer = it.transfer?.copy(progress = progress)) }
                }
                if (clipboard.isCut) _state.update { it.copy(clipboard = null) }
                val message = if (clipboard.isCut) R.plurals.msg_moved else R.plurals.msg_copied
                _events.trySend(BrowserEvent.Message(message, listOf(count), count))
            } catch (e: CancellationException) {
                _events.trySend(BrowserEvent.Message(R.string.msg_transfer_cancelled))
                throw e
            } catch (e: Exception) {
                sendError(e)
            } finally {
                _state.update { it.copy(transfer = null) }
                load()
            }
        }
    }

    fun cancelTransfer() {
        transferJob?.cancel()
    }

    fun shareSelection() {
        val files = _state.value.selectedEntries.filter { !it.isDirectory }
        if (files.isEmpty()) return
        clearSelection()
        _events.trySend(BrowserEvent.Share(files.map { it.path }))
    }

    // endregion

    // region Dialogs & file operations

    fun showDialog(dialog: BrowserDialog) {
        _state.update { it.copy(dialog = dialog) }
    }

    fun dismissDialog() {
        _state.update { it.copy(dialog = null) }
    }

    fun requestRename() {
        _state.value.selectedEntries.singleOrNull()?.let { showDialog(BrowserDialog.Rename(it)) }
    }

    fun requestDelete() {
        val entries = _state.value.selectedEntries
        if (entries.isNotEmpty()) showDialog(BrowserDialog.ConfirmDelete(entries))
    }

    fun createFolder(name: String) = runFsAction { fs, dir -> fs.createDirectory(VfsPaths.child(dir, name)) }

    fun createFile(name: String) = runFsAction { fs, dir -> fs.createFile(VfsPaths.child(dir, name)) }

    fun rename(entry: FileEntry, newName: String) = runFsAction { fs, dir ->
        fs.rename(entry.path, VfsPaths.child(dir, newName))
    }

    fun delete(entries: List<FileEntry>) = runFsAction { fs, _ ->
        for (entry in entries) fs.delete(entry.path)
        _events.trySend(BrowserEvent.Message(R.plurals.msg_deleted, listOf(entries.size), entries.size))
    }

    fun updatePrefs(transform: (BrowserPrefs) -> BrowserPrefs) {
        viewModelScope.launch { preferences.updateBrowser(transform) }
    }

    /** Closes any dialog, runs [action] against the current folder's file system, then reloads. */
    private fun runFsAction(action: suspend (fs: VirtualFileSystem, dir: String) -> Unit) {
        val location = _state.value.location
        _state.update { it.copy(dialog = null, selection = emptySet(), isLoading = true) }
        viewModelScope.launch {
            try {
                action(fileSystems[location.fsId], location.path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendError(e)
            }
            load()
        }
    }

    private fun sendError(e: Exception) {
        _events.trySend(BrowserEvent.Message(R.string.msg_error, listOf(e.message ?: e.javaClass.simpleName)))
    }

    // endregion

    private companion object {
        const val MAX_HISTORY = 100
    }
}

package io.github.linuskromnow.linusexplorer.ui.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.linuskromnow.linusexplorer.LinusExplorerApp
import io.github.linuskromnow.linusexplorer.R
import io.github.linuskromnow.linusexplorer.crypto.AgeNoMatchException
import io.github.linuskromnow.linusexplorer.data.Bookmark
import io.github.linuskromnow.linusexplorer.data.SavedFolders
import io.github.linuskromnow.linusexplorer.editor.TextFiles
import io.github.linuskromnow.linusexplorer.fs.BrowserPrefs
import io.github.linuskromnow.linusexplorer.fs.FileEncryption
import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.FileKind
import io.github.linuskromnow.linusexplorer.fs.FileSorting
import io.github.linuskromnow.linusexplorer.fs.FileTransfer
import io.github.linuskromnow.linusexplorer.fs.Location
import io.github.linuskromnow.linusexplorer.fs.PasswordProtected
import io.github.linuskromnow.linusexplorer.fs.PasswordRequiredFsException
import io.github.linuskromnow.linusexplorer.fs.RangeReadable
import io.github.linuskromnow.linusexplorer.fs.RangeUnsupportedFsException
import io.github.linuskromnow.linusexplorer.fs.TransferProgress
import io.github.linuskromnow.linusexplorer.fs.VfsPaths
import io.github.linuskromnow.linusexplorer.fs.ViewMode
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import io.github.linuskromnow.linusexplorer.fs.archive.ArchiveFileSystem
import io.github.linuskromnow.linusexplorer.fs.archive.ArchiveFormat
import io.github.linuskromnow.linusexplorer.fs.archive.ArchiveSource
import io.github.linuskromnow.linusexplorer.fs.archive.ArchiveWriter
import io.github.linuskromnow.linusexplorer.fs.local.LocalFileSystem
import io.github.linuskromnow.linusexplorer.fs.network.Connection
import io.github.linuskromnow.linusexplorer.fs.network.UntrustedHostKeyException
import io.github.linuskromnow.linusexplorer.fs.properties.Checksums
import io.github.linuskromnow.linusexplorer.fs.properties.FolderSizer
import io.github.linuskromnow.linusexplorer.fs.search.FileSearch
import io.github.linuskromnow.linusexplorer.fs.search.NameMatcher
import io.github.linuskromnow.linusexplorer.fs.search.SearchLimits
import io.github.linuskromnow.linusexplorer.fs.vault.VaultFileSystem
import io.github.linuskromnow.linusexplorer.storage.Place
import io.github.linuskromnow.linusexplorer.storage.Places
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import io.github.linuskromnow.linusexplorer.fs.ConflictResolution
import io.github.linuskromnow.linusexplorer.tasks.RemoteEdit
import io.github.linuskromnow.linusexplorer.tasks.TaskScope
import io.github.linuskromnow.linusexplorer.tasks.TransferKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Environment
import java.io.File
import java.time.LocalDate
import java.util.UUID

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as LinusExplorerApp).container
    private val fileSystems = container.fileSystems
    private val fileTransfer = container.fileTransfer
    private val preferences = container.preferences
    private val savedFolders = container.savedFolders
    private val recycleBin = container.recycleBin
    private val remoteEdits = container.remoteEdits
    private val sharedClipboard = container.clipboard
    val imageLoader = container.imageLoader

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
    private var searchJob: Job? = null

    /** Lets a cancelled search that is still winding down tell it is stale and drop its results. */
    @Volatile private var searchGeneration = 0
    private val tasks = container.tasks

    /** What to retry once the user has typed the password asked for by a [BrowserDialog.Password]. */
    private var afterPassword: (() -> Unit)? = null

    private val vault = container.vault
    private val network = container.network

    /** What to retry once the user trusted a server key in a [BrowserDialog.TrustHost]. */
    private var afterTrust: (() -> Unit)? = null

    /** Needed by the UI to turn fingerprint unlock on (it wraps the key behind a biometric prompt). */
    val vaultBiometrics = container.vaultBiometrics

    init {
        refreshPlaces()
        onVaultBiometricChanged()
        viewModelScope.launch {
            network.store.connections.collect { list -> _state.update { it.copy(connections = list) } }
        }
        viewModelScope.launch {
            recycleBin.restores.drop(1).collect { refresh() }
        }
        viewModelScope.launch {
            savedFolders.data.collect { data -> _state.update { it.copy(bookmarks = data.bookmarks, recents = data.recents) } }
        }
        viewModelScope.launch {
            var first = true
            preferences.browser.collect { prefs ->
                _state.update {
                    // A new pane starts with the saved default; after that its view mode is its own.
                    val viewMode = if (first) prefs.viewMode else it.viewMode
                    it.copy(prefs = prefs, viewMode = viewMode, entries = FileSorting.sort(unsortedEntries, prefs))
                }
                first = false
            }
        }
        viewModelScope.launch {
            container.viewModeForAll.collect { mode -> _state.update { it.copy(viewMode = mode) } }
        }
        viewModelScope.launch {
            var running = false
            tasks.current.collect { transfer ->
                _state.update { it.copy(transfer = transfer, transferHidden = transfer != null && it.transferHidden) }
                // A task started in another tab or pane may have changed this folder too.
                if (running && transfer == null) refresh()
                running = transfer != null
            }
        }
        viewModelScope.launch {
            sharedClipboard.collect { clipboard -> _state.update { it.copy(clipboard = clipboard) } }
        }
        viewModelScope.launch {
            vault.locks.drop(1).collect { leaveVault() }
        }
        load()
    }

    private var started = false

    /** Opens [location] in a freshly created tab; later calls (e.g. after rotation) do nothing. */
    fun startAt(location: Location?) {
        if (started) return
        started = true
        if (location != null && location != _state.value.location) show(location)
    }

    // region Navigation

    fun navigateTo(path: String) = navigateTo(_state.value.location.copy(path = path))

    fun navigateTo(location: Location) {
        closeSearch()
        val current = _state.value.location
        val target = location.copy(path = VfsPaths.normalize(location.path))
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
        _state.update { it.copy(places = places, breadcrumbs = breadcrumbsFor(it.location, places)) }
    }

    fun scrollPosition(location: Location): Pair<Int, Int> = scrollPositions[location] ?: (0 to 0)

    fun saveScrollPosition(location: Location, index: Int, offset: Int) {
        scrollPositions[location] = index to offset
    }

    private fun show(location: Location) {
        closeSearch()
        unsortedEntries = emptyList()
        _state.update {
            it.copy(
                location = location,
                readOnly = fileSystems.find(location.fsId)?.isReadOnly == true,
                entries = emptyList(),
                selection = emptySet(),
                error = null,
                canGoBack = history.isNotEmpty(),
                breadcrumbs = breadcrumbsFor(location, it.places),
            )
        }
        load()
    }

    private fun load() {
        loadJob?.cancel()
        val location = _state.value.location
        if (_state.value.search?.query?.isNotBlank() == true) launchSearch(debounce = false) // results may be stale now
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val entries = fileSystems[location.fsId].list(location.path)
                unsortedEntries = entries
                val paths = entries.mapTo(HashSet()) { it.path }
                _state.update {
                    it.copy(
                        entries = FileSorting.sort(entries, it.prefs),
                        // Search results live deeper than this listing, so their selection stays.
                        selection = if (it.search != null) it.selection else it.selection.filterTo(HashSet()) { path -> path in paths },
                        isLoading = false,
                    )
                }
                recordVisit(location)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unsortedEntries = emptyList()
                _state.update { it.copy(entries = emptyList(), isLoading = false, error = e.message ?: e.toString()) }
                if (e is PasswordRequiredFsException) askForPassword(e, retry = ::load)
                if (e is UntrustedHostKeyException) askToTrustHost(e, retry = ::load)
            }
        }
    }

    private fun breadcrumbsFor(location: Location, places: List<Place>): List<Breadcrumb> {
        val base = if (location.fsId != LocalFileSystem.ID) {
            Breadcrumb(fsName(location.fsId), VfsPaths.ROOT)
        } else {
            places
                .filter { it.isVolume && VfsPaths.isAncestorOrSelf(it.path, location.path) }
                .maxByOrNull { it.path.length }
                ?.let { Breadcrumb(it.label, it.path) }
                ?: Breadcrumb(getApplication<Application>().getString(R.string.place_root), VfsPaths.ROOT)
        }
        var current = base.path
        return listOf(base) + VfsPaths.relativeSegments(base.path, location.path).map { segment ->
            current = VfsPaths.child(current, segment)
            Breadcrumb(segment, current)
        }
    }

    /** Also names saved connections that haven't been opened since the app started. */
    private fun fsName(fsId: String): String =
        fileSystems.find(fsId)?.displayName
            ?: _state.value.connections.find { it.fsId == fsId }?.label
            ?: fsId

    // endregion

    // region Bookmarks & recent folders

    /** Bookmarks the folder being browsed, or removes its bookmark. */
    fun toggleBookmark() {
        val location = _state.value.location
        if (!SavedFolders.canSave(location)) return
        val name = describe(location).title
        val bookmarked = _state.value.isBookmarked
        val message = if (bookmarked) R.string.msg_bookmark_removed else R.string.msg_bookmark_added
        updateSavedFolders(onDone = { _events.trySend(BrowserEvent.Message(message, listOf(name))) }) {
            if (bookmarked) removeBookmark(location) else addBookmark(Bookmark(location, name))
        }
    }

    fun removeBookmark(bookmark: Bookmark) = updateSavedFolders { removeBookmark(bookmark.location) }

    fun clearRecents() = updateSavedFolders { clearRecents() }

    /** Drawer volumes and shortcuts are one tap away already, so visiting them isn't worth a recent entry. */
    private fun recordVisit(location: Location) {
        if (location.fsId == LocalFileSystem.ID && _state.value.places.any { it.path == location.path }) return
        updateSavedFolders { recordVisit(location) }
    }

    private fun updateSavedFolders(onDone: () -> Unit = {}, action: SavedFolders.() -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { savedFolders.action() }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    /** Title and "where is it" line for a bookmarked or recent folder. */
    fun describe(location: Location): FolderLabel {
        val crumbs = breadcrumbsFor(location, _state.value.places)
        val title = if (crumbs.size == 1) crumbs.single().label else VfsPaths.name(location.path)
        return FolderLabel(title, subtitle = crumbs.dropLast(1).joinToString(" › ") { it.label })
    }

    // endregion

    // region Selection & clicks

    fun onEntryClick(entry: FileEntry) {
        val location = _state.value.location
        when {
            _state.value.inSelectionMode -> toggleSelection(entry)
            entry.isDirectory -> navigateTo(entry.path)
            FileEncryption.isEncrypted(entry.name) -> showDialog(BrowserDialog.Decrypt(entry, open = true))
            entry.name.endsWith(VaultFileSystem.BACKUP_EXTENSION) && location.fsId == LocalFileSystem.ID ->
                showDialog(BrowserDialog.RestoreVault(entry))
            ArchiveFormat.detect(entry.name) != null -> openArchive(entry)
            FileKind.of(entry) == FileKind.TEXT -> editFile(entry)
            mayBeText(entry) -> editIfText(entry)
            else -> openExternally(entry)
        }
    }

    /** Extensions that don't say much (".ts" is mostly video): look at the content to decide. */
    private fun mayBeText(entry: FileEntry) = entry.extension.let { it.isEmpty() || it == "ts" }

    private fun editFile(entry: FileEntry) {
        _events.trySend(BrowserEvent.EditFile(Location(_state.value.location.fsId, entry.path)))
    }

    private fun editIfText(entry: FileEntry) {
        val location = _state.value.location
        viewModelScope.launch {
            val isText = try {
                TextFiles.looksLikeText(fileSystems[location.fsId], entry.path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false // the external path below reports unreadable files and asks for passwords
            }
            if (isText) editFile(entry) else openExternally(entry)
        }
    }

    private fun openExternally(entry: FileEntry) {
        if (_state.value.location.fsId == LocalFileSystem.ID) {
            _events.trySend(BrowserEvent.OpenFile(entry.path))
        } else {
            openViaCache(entry)
        }
    }

    /** "Edit" for the single selected file, whatever its extension. */
    fun editSelection() {
        val entry = _state.value.selectedEntries.singleOrNull()?.takeIf { !it.isDirectory } ?: return
        clearSelection()
        editFile(entry)
    }

    fun onEntryLongClick(entry: FileEntry) = toggleSelection(entry)

    fun toggleSelection(entry: FileEntry) {
        _state.update {
            it.copy(selection = if (entry.path in it.selection) it.selection - entry.path else it.selection + entry.path)
        }
    }

    fun selectAll() {
        _state.update { state -> state.copy(selection = state.visibleEntries.mapTo(HashSet()) { it.path }) }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet()) }
    }

    /** "Open with" for the single selected file, bypassing archive browsing. */
    fun openSelectionExternally() {
        val entry = _state.value.selectedEntries.singleOrNull()?.takeIf { !it.isDirectory } ?: return
        clearSelection()
        openExternally(entry)
    }

    // endregion

    // region Search

    fun openSearch() {
        if (_state.value.search != null) return
        _state.update { it.copy(search = SearchState(), selection = emptySet()) }
    }

    fun closeSearch() {
        if (_state.value.search == null) return
        searchGeneration++
        searchJob?.cancel()
        _state.update { it.copy(search = null, selection = emptySet()) }
    }

    fun setSearchQuery(query: String) {
        if (_state.value.search == null) return
        _state.update { state -> state.copy(search = state.search?.copy(query = query)) }
        launchSearch(debounce = true)
    }

    /** Searches the current folder and everything below it, streaming hits into [BrowserUiState.search]. */
    private fun launchSearch(debounce: Boolean) {
        searchJob?.cancel()
        val generation = ++searchGeneration
        val query = _state.value.search?.query.orEmpty()
        val matcher = NameMatcher.parse(query)
        if (matcher == null) {
            updateSearch(generation) { SearchState(query = query) }
            return
        }
        val location = _state.value.location
        val includeHidden = _state.value.prefs.showHidden
        searchJob = viewModelScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS) // don't walk the tree for every keystroke
            updateSearch(generation) { SearchState(query = query, running = true) }
            try {
                val summary = withContext(Dispatchers.IO) {
                    FileSearch.search(
                        fileSystems[location.fsId], location.path, matcher, SearchLimits(includeHidden = includeHidden),
                        onProgress = { scanned -> updateSearch(generation) { it.copy(scanned = scanned) } },
                    ) { hit -> updateSearch(generation) { it.copy(results = it.results + hit) } }
                }
                updateSearch(generation) { it.copy(running = false, truncated = summary.truncated, scanned = summary.scanned) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PasswordRequiredFsException) {
                updateSearch(generation) { it.copy(running = false) }
                askForPassword(e, retry = { launchSearch(debounce = false) })
            } catch (e: UntrustedHostKeyException) {
                updateSearch(generation) { it.copy(running = false) }
                askToTrustHost(e, retry = { launchSearch(debounce = false) })
            } catch (e: Exception) {
                updateSearch(generation) { it.copy(running = false, error = e.message ?: e.toString()) }
            }
        }
    }

    private fun updateSearch(generation: Int, transform: (SearchState) -> SearchState) {
        _state.update { state ->
            val search = state.search
            if (search == null || generation != searchGeneration) state else state.copy(search = transform(search))
        }
    }

    // endregion

    // region Archives

    private fun openArchive(entry: FileEntry) {
        val location = _state.value.location
        val id = ArchiveFileSystem.idFor(location.fsId, entry.path)
        val existing = fileSystems.find(id) as? ArchiveFileSystem
        // Reuse an open view (keeping its password and table of contents) unless the file changed.
        if (existing != null && existing.isCurrent(entry) && (location.fsId == LocalFileSystem.ID || existing.source is ArchiveSource.Remote)) {
            return navigateTo(Location(id, VfsPaths.ROOT))
        }
        if (location.fsId == LocalFileSystem.ID) {
            val archive = registerArchive(id, ArchiveSource.Local(File(entry.path)), entry)
            navigateTo(Location(archive.id, VfsPaths.ROOT))
            return
        }
        val fs = fileSystems[location.fsId]
        runTransfer(TransferKind.OPEN, retry = { openArchive(entry) }) {
            if (fs is RangeReadable && ArchiveFormat.detect(entry.name) == ArchiveFormat.ZIP) {
                // Read just the ZIP's table of contents from the server; files come piece by piece when opened.
                val size = fs.stat(entry.path).size
                val archive = registerArchive(id, ArchiveSource.Remote(fs, entry.path, size, entry.lastModified), entry)
                try {
                    archive.list(VfsPaths.ROOT)
                    navigateTo(Location(archive.id, VfsPaths.ROOT))
                    return@runTransfer
                } catch (e: RangeUnsupportedFsException) {
                    archive.close() // e.g. an encrypted ZIP or a server ignoring Range: download it instead
                }
            }
            // Other archives on servers, and archives inside archives, are unpacked to the cache first.
            val local = copyToCache(location, entry, onProgress)
            val archive = registerArchive(id, ArchiveSource.Local(local), entry)
            navigateTo(Location(archive.id, VfsPaths.ROOT))
        }
    }

    /** Registers a fresh archive view, closing the one it replaces. */
    private fun registerArchive(id: String, source: ArchiveSource, entry: FileEntry): ArchiveFileSystem {
        (fileSystems.find(id) as? ArchiveFileSystem)?.close()
        return ArchiveFileSystem(id, source, ArchiveFormat.detect(entry.name)!!).also { fileSystems.register(it) }
    }

    fun extractSelection() {
        val location = _state.value.location
        val entry = _state.value.selectedEntries.singleOrNull() ?: return
        if (ArchiveFormat.detect(entry.name) == null || location.fsId != LocalFileSystem.ID) return
        clearSelection()
        runTransfer(TransferKind.EXTRACT, retry = { retryExtract(entry) }) {
            val id = ArchiveFileSystem.idFor(location.fsId, entry.path)
            val archive = (fileSystems.find(id) as? ArchiveFileSystem)?.takeIf { it.isCurrent(entry) }
                ?: registerArchive(id, ArchiveSource.Local(File(entry.path)), entry)
            archive.verifyPassword()
            val fs = fileSystems[location.fsId]
            val parent = VfsPaths.parent(entry.path) ?: location.path
            val dirName = FileTransfer.uniqueName(fs, parent, ArchiveFormat.baseName(entry.name), isDirectory = true)
            val dir = VfsPaths.child(parent, dirName)
            fs.createDirectory(dir)
            val roots = archive.list(VfsPaths.ROOT).map { Location(archive.id, it.path) }
            fileTransfer.run(roots, Location(location.fsId, dir), move = false, conflicts, onProgress)
            _events.trySend(BrowserEvent.Message(R.string.msg_extracted, listOf(dirName)))
        }
    }

    private fun retryExtract(entry: FileEntry) {
        _state.update { it.copy(selection = setOf(entry.path)) }
        extractSelection()
    }

    fun requestCompress() {
        val state = _state.value
        val entries = state.selectedEntries
        if (entries.isEmpty() || state.readOnly) return
        val suggested = entries.singleOrNull()?.let { if (it.isDirectory) it.name else it.name.substringBeforeLast('.') }
            ?: state.breadcrumbs.lastOrNull()?.label
            ?: "archive"
        showDialog(BrowserDialog.Compress(entries, suggested))
    }

    fun compress(entries: List<FileEntry>, baseName: String, format: ArchiveFormat, password: String?) {
        val location = _state.value.location
        _state.update { it.copy(dialog = null, selection = emptySet()) }
        runTransfer(TransferKind.COMPRESS, retry = { compress(entries, baseName, format, password) }) {
            val fs = fileSystems[location.fsId]
            val name = FileTransfer.uniqueName(fs, location.path, baseName + format.extension, isDirectory = false)
            val target = VfsPaths.child(location.path, name)
            val sources = entries.map { fs to it }
            val chars = password?.takeIf { it.isNotEmpty() }?.toCharArray()
            if (location.fsId == LocalFileSystem.ID) {
                ArchiveWriter.create(sources, File(target), format, chars, onProgress)
            } else {
                val temp = File(cacheDir("compress"), name)
                try {
                    ArchiveWriter.create(sources, temp, format, chars, onProgress)
                    withContext(Dispatchers.IO) {
                        temp.inputStream().use { input -> fs.openOutput(target).use { input.copyTo(it) } }
                    }
                } finally {
                    temp.delete()
                }
            }
            _events.trySend(BrowserEvent.Message(R.string.msg_compressed, listOf(name)))
        }
    }

    fun submitPassword(dialog: BrowserDialog.Password, password: String) {
        _state.update { it.copy(dialog = null) }
        (fileSystems.find(dialog.fsId) as? PasswordProtected)?.setPassword(password.toCharArray())
        afterPassword?.invoke()
        afterPassword = null
    }

    private fun askForPassword(e: PasswordRequiredFsException, retry: () -> Unit) {
        val fsId = e.fsId ?: return sendError(e)
        afterPassword = retry
        val name = fileSystems.find(fsId)?.displayName ?: fsId
        showDialog(BrowserDialog.Password(fsId, name, e.wrongPassword))
    }

    // endregion

    // region Encryption & vault

    fun requestEncrypt() {
        val files = _state.value.selectedEntries.filter { !it.isDirectory }
        if (files.isNotEmpty() && !_state.value.readOnly) showDialog(BrowserDialog.Encrypt(files))
    }

    fun encrypt(files: List<FileEntry>, password: String, deleteOriginals: Boolean) {
        val location = _state.value.location
        _state.update { it.copy(dialog = null, selection = emptySet()) }
        runTransfer(TransferKind.ENCRYPT, retry = {}) {
            val count = FileEncryption.encrypt(fileSystems[location.fsId], files, password.toCharArray(), deleteOriginals, onProgress)
            _events.trySend(BrowserEvent.Message(R.plurals.msg_encrypted, listOf(count), count))
        }
    }

    fun requestDecrypt() {
        val entry = _state.value.selectedEntries.singleOrNull()?.takeIf { FileEncryption.isEncrypted(it.name) } ?: return
        if (!_state.value.readOnly) showDialog(BrowserDialog.Decrypt(entry, open = false))
    }

    fun decrypt(dialog: BrowserDialog.Decrypt, password: String, deleteEncrypted: Boolean) {
        val location = _state.value.location
        _state.update { it.copy(dialog = null, selection = emptySet()) }
        val kind = if (dialog.open) TransferKind.OPEN else TransferKind.DECRYPT
        runTransfer(kind, retry = {}) {
            val fs = fileSystems[location.fsId]
            try {
                if (dialog.open) {
                    val dir = File(cacheDir(OPEN_CACHE), UUID.randomUUID().toString()).apply { mkdirs() }
                    val local = fileSystems[LocalFileSystem.ID]
                    val path = FileEncryption.decrypt(fs, dialog.entry, local, dir.path, password.toCharArray(), onProgress)
                    _events.trySend(BrowserEvent.OpenFile(path))
                } else {
                    val path = FileEncryption.decrypt(fs, dialog.entry, fs, VfsPaths.parent(dialog.entry.path) ?: location.path, password.toCharArray(), onProgress)
                    if (deleteEncrypted) fs.delete(dialog.entry.path)
                    _events.trySend(BrowserEvent.Message(R.string.msg_decrypted, listOf(VfsPaths.name(path))))
                }
            } catch (e: AgeNoMatchException) {
                showDialog(dialog.copy(wrongPassword = true))
            }
        }
    }

    fun vaultMasterKey(): ByteArray? = vault.masterKeyCopy()

    fun onVaultBiometricChanged() {
        _state.update { it.copy(vaultBiometric = vaultBiometrics.isEnabled) }
    }

    fun unlockVaultWithKey(key: ByteArray) {
        _state.update { it.copy(dialog = null) }
        viewModelScope.launch {
            try {
                vault.unlockWithKey(key)
                afterPassword?.invoke()
                afterPassword = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendError(e)
            } finally {
                key.fill(0)
            }
        }
    }

    /** Saves the (still encrypted) vault as one file in Downloads, so it survives reinstalling the app. */
    fun backupVault() {
        runTransfer(TransferKind.BACKUP, retry = {}) {
            val local = fileSystems[LocalFileSystem.ID]
            val downloads = File(Places.primaryStoragePath(), Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }.path
            val name = FileTransfer.uniqueName(local, downloads, "LinusExplorer-vault-${LocalDate.now()}${VaultFileSystem.BACKUP_EXTENSION}", false)
            local.openOutput(VfsPaths.child(downloads, name)).use { vault.backupTo(it) }
            _events.trySend(BrowserEvent.Message(R.string.msg_vault_backed_up, listOf(name)))
        }
    }

    fun restoreVault(entry: FileEntry) {
        val location = _state.value.location
        _state.update { it.copy(dialog = null) }
        runTransfer(TransferKind.RESTORE, retry = {}) {
            fileSystems[location.fsId].openInput(entry.path).use { vault.restoreFrom(it) }
            vaultBiometrics.disable() // the restored vault has a different master key
            onVaultBiometricChanged()
            history.removeAll { it.fsId == VaultFileSystem.ID }
            _events.trySend(BrowserEvent.Message(R.string.msg_vault_restored))
        }
    }

    // region Network connections

    fun openConnection(connection: Connection) {
        val fs = network.open(connection)
        navigateTo(Location(fs.id, connection.path))
    }

    fun saveConnection(connection: Connection) {
        _state.update { it.copy(dialog = null) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { network.save(connection) }
            if (_state.value.location.fsId == connection.fsId) refresh() // reconnect with the new settings
        }
    }

    fun deleteConnection(connection: Connection) {
        _state.update { it.copy(dialog = null) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                network.remove(connection)
                savedFolders.forget(connection.fsId)
            }
            history.removeAll { it.fsId == connection.fsId }
            if (_state.value.location.fsId == connection.fsId) show(Location(LocalFileSystem.ID, Places.primaryStoragePath()))
        }
    }

    private fun askToTrustHost(e: UntrustedHostKeyException, retry: () -> Unit) {
        afterTrust = retry
        showDialog(BrowserDialog.TrustHost(e))
    }

    fun trustHost(dialog: BrowserDialog.TrustHost) {
        _state.update { it.copy(dialog = null) }
        val e = dialog.error
        network.knownHosts.trust(e.host, e.port, e.key)
        afterTrust?.invoke()
        afterTrust = null
    }

    // endregion

    fun openVault() {
        if (vault.isCreated) navigateTo(Location(VaultFileSystem.ID, VfsPaths.ROOT)) else showDialog(BrowserDialog.CreateVault)
    }

    fun createVault(password: String) {
        _state.update { it.copy(dialog = null) }
        viewModelScope.launch {
            try {
                vault.create(password.toCharArray())
                navigateTo(Location(VaultFileSystem.ID, VfsPaths.ROOT))
                _events.trySend(BrowserEvent.Message(R.string.msg_vault_created))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    fun changeVaultPassword(password: String) = runFsAction { _, _ ->
        vault.changePassword(password.toCharArray())
        _events.trySend(BrowserEvent.Message(R.string.msg_vault_password_changed))
    }

    /** Locks the vault, wipes decrypted temp copies and leaves the vault if it is on screen. */
    fun lockVault() {
        if (!vault.isUnlocked) return
        vault.lock()
        imageLoader.memoryCache?.clear() // may hold decrypted vault thumbnails
        // Remote files being edited elsewhere aren't vault content; only the decrypted copies go.
        viewModelScope.launch(Dispatchers.IO) { remoteEdits.cleanCache(File(getApplication<Application>().cacheDir, OPEN_CACHE), keepUnchanged = true) }
        _events.trySend(BrowserEvent.Message(R.string.msg_vault_locked))
    }

    /** Runs in every tab and pane once the vault is locked, wherever that happened. */
    private fun leaveVault() {
        history.removeAll { it.fsId == VaultFileSystem.ID }
        if (_state.value.inVault) show(Location(LocalFileSystem.ID, Places.primaryStoragePath()))
        _state.update { it.copy(canGoBack = history.isNotEmpty()) }
    }

    fun moveSelectionToVault() {
        val state = _state.value
        if (!vault.isCreated) return showDialog(BrowserDialog.CreateVault)
        val items = state.selectedEntries.map { Location(state.location.fsId, it.path) }
        if (items.isEmpty() || state.readOnly || state.inVault) return
        clearSelection()
        moveToVault(items)
    }

    private fun moveToVault(items: List<Location>) {
        runTransfer(TransferKind.MOVE, retry = { moveToVault(items) }) { moveToVault(items, onProgress) }
    }

    private suspend fun moveToVault(items: List<Location>, onProgress: (TransferProgress) -> Unit) {
        vault.list(VfsPaths.ROOT) // asks for the password first if the vault is locked
        val count = fileTransfer.run(items, Location(VaultFileSystem.ID, VfsPaths.ROOT), move = true, onProgress = onProgress)
        _events.trySend(BrowserEvent.Message(R.plurals.msg_moved_to_vault, listOf(count), count))
    }

    /**
     * Called by [BrowserHost] (once for the whole app, not per tab) when the app is back after
     * [awayMillis] in the background: auto-locks the vault after [VAULT_AUTO_LOCK_MS].
     */
    fun onAppForeground(awayMillis: Long) {
        if (awayMillis >= VAULT_AUTO_LOCK_MS) lockVault()
        checkRemoteEdits() // the user may be back from editing a remote file in another app
    }

    // endregion

    // region Clipboard & transfers

    fun copySelection(cut: Boolean) {
        val state = _state.value
        if (cut && state.readOnly) return
        val items = state.selectedEntries.map { Location(state.location.fsId, it.path) }
        if (items.isEmpty()) return
        sharedClipboard.value = Clipboard(items, cut)
        _state.update { it.copy(selection = emptySet()) }
        val message = if (cut) R.plurals.msg_cut_to_clipboard else R.plurals.msg_copied_to_clipboard
        _events.trySend(BrowserEvent.Message(message, listOf(items.size), items.size))
    }

    fun clearClipboard() {
        sharedClipboard.value = null
    }

    fun paste() {
        val clipboard = _state.value.clipboard ?: return
        val target = _state.value.location
        val kind = if (clipboard.isCut) TransferKind.MOVE else TransferKind.COPY
        runTransfer(kind, retry = ::paste) {
            for (fsId in clipboard.items.mapTo(HashSet()) { it.fsId }) {
                (fileSystems.find(fsId) as? ArchiveFileSystem)?.verifyPassword()
            }
            val count = fileTransfer.run(clipboard.items, target, clipboard.isCut, conflicts, onProgress)
            if (clipboard.isCut) sharedClipboard.value = null
            val message = if (clipboard.isCut) R.plurals.msg_moved else R.plurals.msg_copied
            _events.trySend(BrowserEvent.Message(message, listOf(count), count))
        }
    }

    fun cancelTransfer() = tasks.cancel()

    /** Keeps the task running but closes its dialog; progress stays visible in the notification. */
    fun hideTransfer() {
        _state.update { it.copy(transferHidden = true) }
    }

    fun showTransfer() {
        _state.update { it.copy(transferHidden = false) }
    }

    fun resolveConflict(resolution: ConflictResolution, applyToAll: Boolean) = tasks.answerConflict(resolution, applyToAll)

    fun shareSelection() {
        val files = _state.value.selectedEntries.filter { !it.isDirectory }
        if (files.isEmpty() || _state.value.location.fsId != LocalFileSystem.ID) return
        clearSelection()
        _events.trySend(BrowserEvent.Share(files.map { it.path }))
    }

    /**
     * Copies a non-local file into the cache, then hands it to another app. Copies of network files
     * are tracked so changes saved by that app can be uploaded back (see [checkRemoteEdits]).
     */
    private fun openViaCache(entry: FileEntry) {
        val location = _state.value.location
        runTransfer(TransferKind.OPEN, retry = { openViaCache(entry) }) {
            val local = copyToCache(location, entry, onProgress)
            if (Connection.connectionIdOf(location.fsId) != null) {
                // Same source as the later check: listings and single-file lookups can differ in precision (FTP).
                val remoteModified = remoteModified(location.fsId, entry.path)
                withContext(Dispatchers.IO) { remoteEdits.track(Location(location.fsId, entry.path), local, remoteModified) }
            }
            _events.trySend(BrowserEvent.OpenFile(local.path))
        }
    }

    // region Uploading edits of remote files

    /** Offers the next locally edited remote file for upload, unless something else is on screen. */
    fun checkRemoteEdits() {
        viewModelScope.launch {
            val edit = withContext(Dispatchers.IO) { remoteEdits.changed().firstOrNull() } ?: return@launch
            val remote = remoteModified(edit.source.fsId, edit.source.path) // 0 when offline: the upload itself will tell
            val remoteChanged = remote != 0L && edit.remoteModified != 0L && remote != edit.remoteModified
            if (_state.value.dialog == null && _state.value.transfer == null) {
                showDialog(BrowserDialog.UploadChanges(edit, remoteChanged))
            }
        }
    }

    fun uploadChanges(edit: RemoteEdit) {
        _state.update { it.copy(dialog = null) }
        runTransfer(TransferKind.UPLOAD, retry = { uploadChanges(edit) }) {
            val fs = fileSystems[edit.source.fsId]
            val local = File(edit.localPath)
            val total = local.length()
            withContext(Dispatchers.IO) {
                local.inputStream().use { input ->
                    fs.openOutput(edit.source.path).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(TransferProgress(0, 1, done, total, edit.name))
                        }
                    }
                }
            }
            val remoteModified = remoteModified(edit.source.fsId, edit.source.path)
            withContext(Dispatchers.IO) { remoteEdits.markUploaded(edit, remoteModified) }
            _events.trySend(BrowserEvent.Message(R.string.msg_uploaded, listOf(edit.name)))
        }
    }

    /** The remote file's modification time from a single-file lookup, or 0 if it can't be read. */
    private suspend fun remoteModified(fsId: String, path: String): Long = try {
        fileSystems[fsId].stat(path).lastModified
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        0L
    }

    /** Throws the local changes away. */
    fun discardChanges(edit: RemoteEdit) {
        _state.update { it.copy(dialog = null) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                remoteEdits.forget(edit)
                File(edit.localPath).parentFile?.deleteRecursively()
            }
            checkRemoteEdits()
        }
    }

    // endregion

    private suspend fun copyToCache(location: Location, entry: FileEntry, onProgress: (TransferProgress) -> Unit): File {
        (fileSystems.find(location.fsId) as? ArchiveFileSystem)?.verifyPassword()
        val dir = File(cacheDir(OPEN_CACHE), UUID.randomUUID().toString()).apply { mkdirs() }
        fileTransfer.run(listOf(Location(location.fsId, entry.path)), Location(LocalFileSystem.ID, dir.path), move = false, onProgress = onProgress)
        return File(dir, entry.name)
    }

    private fun cacheDir(name: String) = File(getApplication<Application>().cacheDir, name).apply { mkdirs() }

    /**
     * Runs a long operation as a background task (see [io.github.linuskromnow.linusexplorer.tasks.TaskManager]).
     * A [PasswordRequiredFsException] opens the password dialog and runs [retry] once a password was entered.
     */
    private fun runTransfer(kind: TransferKind, retry: () -> Unit, block: suspend TaskScope.() -> Unit) {
        _state.update { it.copy(transferHidden = false) }
        val started = tasks.start(kind) {
            try {
                block()
            } catch (e: CancellationException) {
                _events.trySend(BrowserEvent.Message(R.string.msg_transfer_cancelled))
                throw e
            } catch (e: PasswordRequiredFsException) {
                askForPassword(e, retry)
            } catch (e: UntrustedHostKeyException) {
                askToTrustHost(e, retry)
            } catch (e: Exception) {
                sendError(e)
            } finally {
                load()
            }
        }
        if (!started) _events.trySend(BrowserEvent.Message(R.string.msg_task_busy))
    }

    // endregion

    // region Properties

    private val _properties = MutableStateFlow<PropertiesState?>(null)
    val properties: StateFlow<PropertiesState?> = _properties.asStateFlow()
    private var measureJob: Job? = null
    private var hashJob: Job? = null

    /** Properties of the selected items (also works on search results). */
    fun requestProperties() {
        val entries = _state.value.selectedEntries
        if (entries.isNotEmpty()) showProperties(entries)
    }

    /** Properties of the folder being browsed. */
    fun requestFolderProperties() {
        val location = _state.value.location
        viewModelScope.launch {
            try {
                showProperties(listOf(fileSystems[location.fsId].stat(location.path)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    private fun showProperties(entries: List<FileEntry>) {
        val fsId = _state.value.location.fsId
        endProperties()
        _properties.value = PropertiesState(entries, fsId, measuring = entries.any { it.isDirectory })
        showDialog(BrowserDialog.Properties(entries))
        val fs = fileSystems[fsId]
        measureJob = viewModelScope.launch {
            if (fsId == LocalFileSystem.ID && entries.size == 1) {
                val file = File(entries.single().path)
                val access = withContext(Dispatchers.IO) { LocalAccess(file.canRead(), file.canWrite(), file.canExecute()) }
                updateProperties { it.copy(access = access) }
            }
            if (entries.none { it.isDirectory }) return@launch
            try {
                val total = withContext(Dispatchers.IO) {
                    FolderSizer.measure(fs, entries) { stats -> updateProperties { it.copy(stats = stats) } }
                }
                updateProperties { it.copy(stats = total, measuring = false) }
            } catch (e: CancellationException) {
                updateProperties { it.copy(measuring = false) }
                throw e
            } catch (e: Exception) {
                updateProperties { it.copy(measuring = false, error = e.message ?: e.toString()) }
            }
        }
    }

    /** Stops the folder measurement (the numbers so far stay visible). */
    fun cancelMeasure() {
        measureJob?.cancel()
    }

    fun computeChecksums() {
        val state = _properties.value ?: return
        val entry = state.single?.takeIf { !it.isDirectory } ?: return
        if (state.hashing) return
        val fs = fileSystems[state.fsId]
        updateProperties { it.copy(hashing = true, hashProgress = 0f, checksums = emptyMap(), error = null) }
        hashJob = viewModelScope.launch {
            try {
                val sums = Checksums.compute(fs, entry.path) { read ->
                    val fraction = if (entry.size > 0) (read.toFloat() / entry.size).coerceIn(0f, 1f) else 1f
                    updateProperties { it.copy(hashProgress = fraction) }
                }
                updateProperties { it.copy(checksums = sums, hashing = false) }
            } catch (e: CancellationException) {
                updateProperties { it.copy(hashing = false) }
                throw e
            } catch (e: Exception) {
                updateProperties { it.copy(hashing = false, error = e.message ?: e.toString()) }
            }
        }
    }

    fun cancelChecksums() {
        hashJob?.cancel()
    }

    fun onChecksumCopied() {
        _events.trySend(BrowserEvent.Message(R.string.msg_checksum_copied))
    }

    private fun updateProperties(transform: (PropertiesState) -> PropertiesState) {
        _properties.update { it?.let(transform) }
    }

    private fun endProperties() {
        measureJob?.cancel()
        hashJob?.cancel()
        _properties.value = null
    }

    // endregion

    // region Dialogs & file operations

    fun showDialog(dialog: BrowserDialog) {
        _state.update { it.copy(dialog = dialog) }
    }

    fun dismissDialog() {
        afterPassword = null
        if (_state.value.dialog is BrowserDialog.Properties) endProperties()
        _state.update { it.copy(dialog = null) }
    }

    fun requestRename() {
        if (_state.value.readOnly) return
        _state.value.selectedEntries.singleOrNull()?.let { showDialog(BrowserDialog.Rename(it)) }
    }

    fun requestDelete() {
        val state = _state.value
        val entries = state.selectedEntries
        if (entries.isEmpty() || state.readOnly) return
        showDialog(BrowserDialog.ConfirmDelete(entries, toBin = entries.all { usesRecycleBin(it.path) }))
    }

    private fun usesRecycleBin(path: String): Boolean {
        val state = _state.value
        return state.prefs.recycleBin && state.location.fsId == LocalFileSystem.ID && recycleBin.accepts(path)
    }

    fun createFolder(name: String) = runFsAction { fs, dir -> fs.createDirectory(VfsPaths.child(dir, name)) }

    fun createFile(name: String) = runFsAction { fs, dir -> fs.createFile(VfsPaths.child(dir, name)) }

    fun rename(entry: FileEntry, newName: String) = runFsAction { fs, dir ->
        fs.rename(entry.path, VfsPaths.child(VfsPaths.parent(entry.path) ?: dir, newName)) // entry may be a search result
    }

    fun delete(entries: List<FileEntry>) {
        val location = _state.value.location
        _state.update { it.copy(dialog = null, selection = emptySet()) }
        val toBin = entries.filter { usesRecycleBin(it.path) }.mapTo(HashSet()) { it.path } // decided while still on this folder
        runTransfer(TransferKind.DELETE, retry = {}) {
            val fs = fileSystems[location.fsId]
            var trashed = 0
            entries.forEachIndexed { index, entry ->
                onProgress(TransferProgress(index, entries.size, index.toLong(), entries.size.toLong(), entry.name))
                // A bin on another mount than the file (rare bind mounts) can't take it; delete as asked.
                if (entry.path in toBin && recycleBin.trash(entry.path)) trashed++ else fs.delete(entry.path)
            }
            val message = if (trashed == entries.size) R.plurals.msg_moved_to_bin else R.plurals.msg_deleted
            _events.trySend(BrowserEvent.Message(message, listOf(entries.size), entries.size))
        }
    }

    /** Switches only this pane, and remembers the choice for new panes and the next start. */
    fun toggleViewMode() {
        val mode = if (_state.value.viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID
        _state.update { it.copy(viewMode = mode) }
        updatePrefs { it.copy(viewMode = mode) }
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

    companion object {
        private const val MAX_HISTORY = 100
        private const val VAULT_AUTO_LOCK_MS = 60_000L
        private const val SEARCH_DEBOUNCE_MS = 300L

        /** Cache sub-folder for files handed to other apps; cleared on every app start. */
        const val OPEN_CACHE = "open"
    }
}

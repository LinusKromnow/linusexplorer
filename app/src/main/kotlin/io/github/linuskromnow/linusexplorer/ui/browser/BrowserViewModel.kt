package io.github.linuskromnow.linusexplorer.ui.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.linuskromnow.linusexplorer.LinusExplorerApp
import io.github.linuskromnow.linusexplorer.R
import io.github.linuskromnow.linusexplorer.crypto.AgeNoMatchException
import io.github.linuskromnow.linusexplorer.fs.BrowserPrefs
import io.github.linuskromnow.linusexplorer.fs.FileEncryption
import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.FileSorting
import io.github.linuskromnow.linusexplorer.fs.FileTransfer
import io.github.linuskromnow.linusexplorer.fs.Location
import io.github.linuskromnow.linusexplorer.fs.PasswordProtected
import io.github.linuskromnow.linusexplorer.fs.PasswordRequiredFsException
import io.github.linuskromnow.linusexplorer.fs.TransferProgress
import io.github.linuskromnow.linusexplorer.fs.VfsPaths
import io.github.linuskromnow.linusexplorer.fs.VirtualFileSystem
import io.github.linuskromnow.linusexplorer.fs.archive.ArchiveFileSystem
import io.github.linuskromnow.linusexplorer.fs.archive.ArchiveFormat
import io.github.linuskromnow.linusexplorer.fs.archive.ArchiveWriter
import io.github.linuskromnow.linusexplorer.fs.local.LocalFileSystem
import io.github.linuskromnow.linusexplorer.fs.vault.VaultFileSystem
import io.github.linuskromnow.linusexplorer.storage.Place
import io.github.linuskromnow.linusexplorer.storage.Places
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import io.github.linuskromnow.linusexplorer.fs.ConflictResolution
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
    private val tasks = container.tasks

    /** What to retry once the user has typed the password asked for by a [BrowserDialog.Password]. */
    private var afterPassword: (() -> Unit)? = null

    private val vault = container.vault

    /** Needed by the UI to turn fingerprint unlock on (it wraps the key behind a biometric prompt). */
    val vaultBiometrics = container.vaultBiometrics
    private var backgroundSince: Long? = null

    init {
        refreshPlaces()
        onVaultBiometricChanged()
        viewModelScope.launch {
            preferences.browser.collect { prefs ->
                _state.update { it.copy(prefs = prefs, entries = FileSorting.sort(unsortedEntries, prefs)) }
            }
        }
        viewModelScope.launch {
            tasks.current.collect { transfer ->
                _state.update { it.copy(transfer = transfer, transferHidden = transfer != null && it.transferHidden) }
            }
        }
        load()
    }

    // region Navigation

    fun navigateTo(path: String) = navigateTo(_state.value.location.copy(path = path))

    fun navigateTo(location: Location) {
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
                if (e is PasswordRequiredFsException) askForPassword(e, retry = ::load)
            }
        }
    }

    private fun breadcrumbsFor(location: Location, places: List<Place>): List<Breadcrumb> {
        val base = if (location.fsId != LocalFileSystem.ID) {
            Breadcrumb(fileSystems.find(location.fsId)?.displayName ?: location.fsId, VfsPaths.ROOT)
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
            location.fsId == LocalFileSystem.ID -> _events.trySend(BrowserEvent.OpenFile(entry.path))
            else -> openViaCache(entry)
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

    /** "Open with" for the single selected file, bypassing archive browsing. */
    fun openSelectionExternally() {
        val entry = _state.value.selectedEntries.singleOrNull()?.takeIf { !it.isDirectory } ?: return
        clearSelection()
        if (_state.value.location.fsId == LocalFileSystem.ID) {
            _events.trySend(BrowserEvent.OpenFile(entry.path))
        } else {
            openViaCache(entry)
        }
    }

    // endregion

    // region Archives

    private fun openArchive(entry: FileEntry) {
        val location = _state.value.location
        if (location.fsId == LocalFileSystem.ID) {
            val archive = archiveFor(location.fsId, entry, File(entry.path))
            navigateTo(Location(archive.id, VfsPaths.ROOT))
            return
        }
        // Archives inside archives (or, later, on remote storage) are unpacked to the cache first.
        runTransfer(TransferKind.OPEN, retry = { openArchive(entry) }) {
            val local = copyToCache(location, entry, onProgress)
            val archive = archiveFor(location.fsId, entry, local)
            navigateTo(Location(archive.id, VfsPaths.ROOT))
        }
    }

    /** Reuses an already registered archive view (keeping its password) unless the file changed. */
    private fun archiveFor(sourceFsId: String, entry: FileEntry, file: File): ArchiveFileSystem {
        val id = ArchiveFileSystem.idFor(sourceFsId, entry.path)
        val existing = fileSystems.find(id) as? ArchiveFileSystem
        if (existing != null && existing.file == file && existing.isCurrent) return existing
        return ArchiveFileSystem(id, file, ArchiveFormat.detect(entry.name)!!).also { fileSystems.register(it) }
    }

    fun extractSelection() {
        val location = _state.value.location
        val entry = _state.value.selectedEntries.singleOrNull() ?: return
        if (ArchiveFormat.detect(entry.name) == null || location.fsId != LocalFileSystem.ID) return
        clearSelection()
        runTransfer(TransferKind.EXTRACT, retry = { retryExtract(entry) }) {
            val archive = archiveFor(location.fsId, entry, File(entry.path))
            archive.verifyPassword()
            val fs = fileSystems[location.fsId]
            val dirName = FileTransfer.uniqueName(fs, location.path, ArchiveFormat.baseName(entry.name), isDirectory = true)
            val dir = VfsPaths.child(location.path, dirName)
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
                    val path = FileEncryption.decrypt(fs, dialog.entry, fs, location.path, password.toCharArray(), onProgress)
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
        viewModelScope.launch(Dispatchers.IO) { File(getApplication<Application>().cacheDir, OPEN_CACHE).deleteRecursively() }
        if (_state.value.inVault) {
            history.removeAll { it.fsId == VaultFileSystem.ID }
            show(Location(LocalFileSystem.ID, Places.primaryStoragePath()))
        }
        _events.trySend(BrowserEvent.Message(R.string.msg_vault_locked))
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

    /** Called when the app goes to the background; see [onAppForeground]. */
    fun onAppBackground() {
        backgroundSince = System.currentTimeMillis()
    }

    /** Auto-locks the vault after the app spent [VAULT_AUTO_LOCK_MS] in the background. */
    fun onAppForeground() {
        val since = backgroundSince ?: return
        backgroundSince = null
        if (System.currentTimeMillis() - since >= VAULT_AUTO_LOCK_MS) lockVault()
    }

    // endregion

    // region Clipboard & transfers

    fun copySelection(cut: Boolean) {
        val state = _state.value
        if (cut && state.readOnly) return
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
        val target = _state.value.location
        val kind = if (clipboard.isCut) TransferKind.MOVE else TransferKind.COPY
        runTransfer(kind, retry = ::paste) {
            for (fsId in clipboard.items.mapTo(HashSet()) { it.fsId }) {
                (fileSystems.find(fsId) as? ArchiveFileSystem)?.verifyPassword()
            }
            val count = fileTransfer.run(clipboard.items, target, clipboard.isCut, conflicts, onProgress)
            if (clipboard.isCut) _state.update { it.copy(clipboard = null) }
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

    /** Copies a non-local file into the cache, then hands it to another app. */
    private fun openViaCache(entry: FileEntry) {
        val location = _state.value.location
        runTransfer(TransferKind.OPEN, retry = { openViaCache(entry) }) {
            val local = copyToCache(location, entry, onProgress)
            _events.trySend(BrowserEvent.OpenFile(local.path))
        }
    }

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
            } catch (e: Exception) {
                sendError(e)
            } finally {
                load()
            }
        }
        if (!started) _events.trySend(BrowserEvent.Message(R.string.msg_task_busy))
    }

    // endregion

    // region Dialogs & file operations

    fun showDialog(dialog: BrowserDialog) {
        _state.update { it.copy(dialog = dialog) }
    }

    fun dismissDialog() {
        afterPassword = null
        _state.update { it.copy(dialog = null) }
    }

    fun requestRename() {
        if (_state.value.readOnly) return
        _state.value.selectedEntries.singleOrNull()?.let { showDialog(BrowserDialog.Rename(it)) }
    }

    fun requestDelete() {
        val entries = _state.value.selectedEntries
        if (entries.isNotEmpty() && !_state.value.readOnly) showDialog(BrowserDialog.ConfirmDelete(entries))
    }

    fun createFolder(name: String) = runFsAction { fs, dir -> fs.createDirectory(VfsPaths.child(dir, name)) }

    fun createFile(name: String) = runFsAction { fs, dir -> fs.createFile(VfsPaths.child(dir, name)) }

    fun rename(entry: FileEntry, newName: String) = runFsAction { fs, dir ->
        fs.rename(entry.path, VfsPaths.child(dir, newName))
    }

    fun delete(entries: List<FileEntry>) {
        val location = _state.value.location
        _state.update { it.copy(dialog = null, selection = emptySet()) }
        runTransfer(TransferKind.DELETE, retry = {}) {
            val fs = fileSystems[location.fsId]
            entries.forEachIndexed { index, entry ->
                onProgress(TransferProgress(index, entries.size, index.toLong(), entries.size.toLong(), entry.name))
                fs.delete(entry.path)
            }
            _events.trySend(BrowserEvent.Message(R.plurals.msg_deleted, listOf(entries.size), entries.size))
        }
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

        /** Cache sub-folder for files handed to other apps; cleared on every app start. */
        const val OPEN_CACHE = "open"
    }
}

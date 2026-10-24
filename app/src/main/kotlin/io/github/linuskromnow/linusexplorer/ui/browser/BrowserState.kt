package io.github.linuskromnow.linusexplorer.ui.browser

import android.content.res.Resources
import io.github.linuskromnow.linusexplorer.fs.BrowserPrefs
import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.Location
import io.github.linuskromnow.linusexplorer.fs.search.SearchHit
import io.github.linuskromnow.linusexplorer.tasks.TransferState
import io.github.linuskromnow.linusexplorer.fs.vault.VaultFileSystem
import io.github.linuskromnow.linusexplorer.storage.Place

data class BrowserUiState(
    val location: Location,
    val readOnly: Boolean = false,
    val breadcrumbs: List<Breadcrumb> = emptyList(),
    val entries: List<FileEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selection: Set<String> = emptySet(),
    val clipboard: Clipboard? = null,
    val prefs: BrowserPrefs = BrowserPrefs(),
    val places: List<Place> = emptyList(),
    val canGoBack: Boolean = false,
    val dialog: BrowserDialog? = null,
    val transfer: TransferState? = null,
    /** The user sent the running task to the background; only a slim progress bar remains. */
    val transferHidden: Boolean = false,
    /** Whether the vault can be unlocked with a fingerprint. */
    val vaultBiometric: Boolean = false,
    /** Non-null while the search bar is open; its results replace the folder listing. */
    val search: SearchState? = null,
) {
    val inSelectionMode: Boolean get() = selection.isNotEmpty()
    val inVault: Boolean get() = location.fsId == VaultFileSystem.ID
    /** What the user currently sees: the search results while searching, else the folder content. */
    val visibleEntries: List<FileEntry> get() = search?.results?.map { it.entry } ?: entries
    val selectedEntries: List<FileEntry> get() = visibleEntries.filter { it.path in selection }
}

data class SearchState(
    val query: String = "",
    /** In the order found, shallowest first. */
    val results: List<SearchHit> = emptyList(),
    val running: Boolean = false,
    /** Entries looked at so far. */
    val scanned: Int = 0,
    /** Stopped at the result limit. */
    val truncated: Boolean = false,
    val error: String? = null,
)

data class Breadcrumb(val label: String, val path: String)

data class Clipboard(val items: List<Location>, val isCut: Boolean)


sealed interface BrowserDialog {
    data object NewFolder : BrowserDialog
    data object NewFile : BrowserDialog
    data class Rename(val entry: FileEntry) : BrowserDialog
    data class ConfirmDelete(val entries: List<FileEntry>) : BrowserDialog
    data class Compress(val entries: List<FileEntry>, val suggestedName: String) : BrowserDialog
    data class Password(val fsId: String, val name: String, val wrongPassword: Boolean) : BrowserDialog
    data class Encrypt(val entries: List<FileEntry>) : BrowserDialog

    /** [open] decrypts to a temporary copy and opens it instead of saving next to the original. */
    data class Decrypt(val entry: FileEntry, val open: Boolean, val wrongPassword: Boolean = false) : BrowserDialog
    data object CreateVault : BrowserDialog
    data object ChangeVaultPassword : BrowserDialog
    data class RestoreVault(val entry: FileEntry) : BrowserDialog
}

sealed interface BrowserEvent {
    /** A string or plurals resource; [quantity] selects the plural form when set. */
    data class Message(val resId: Int, val args: List<Any> = emptyList(), val quantity: Int? = null) : BrowserEvent {
        fun resolve(resources: Resources): String =
            if (quantity != null) {
                resources.getQuantityString(resId, quantity, *args.toTypedArray())
            } else {
                resources.getString(resId, *args.toTypedArray())
            }
    }

    data class OpenFile(val path: String) : BrowserEvent

    /** Open the built-in text editor on [location]. */
    data class EditFile(val location: Location) : BrowserEvent
    data class Share(val paths: List<String>) : BrowserEvent
}

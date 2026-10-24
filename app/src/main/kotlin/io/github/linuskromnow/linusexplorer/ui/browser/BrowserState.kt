package io.github.linuskromnow.linusexplorer.ui.browser

import android.content.res.Resources
import io.github.linuskromnow.linusexplorer.fs.BrowserPrefs
import io.github.linuskromnow.linusexplorer.fs.FileEntry
import io.github.linuskromnow.linusexplorer.fs.FileTransfer
import io.github.linuskromnow.linusexplorer.fs.Location
import io.github.linuskromnow.linusexplorer.storage.Place

data class BrowserUiState(
    val location: Location,
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
) {
    val inSelectionMode: Boolean get() = selection.isNotEmpty()
    val selectedEntries: List<FileEntry> get() = entries.filter { it.path in selection }
}

data class Breadcrumb(val label: String, val path: String)

data class Clipboard(val items: List<Location>, val isCut: Boolean)

data class TransferState(val isMove: Boolean, val progress: FileTransfer.Progress? = null)

sealed interface BrowserDialog {
    data object NewFolder : BrowserDialog
    data object NewFile : BrowserDialog
    data class Rename(val entry: FileEntry) : BrowserDialog
    data class ConfirmDelete(val entries: List<FileEntry>) : BrowserDialog
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
    data class Share(val paths: List<String>) : BrowserEvent
}

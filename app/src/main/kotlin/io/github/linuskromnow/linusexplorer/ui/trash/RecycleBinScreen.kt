package io.github.linuskromnow.linusexplorer.ui.trash

import android.app.Application
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linuskromnow.linusexplorer.LinusExplorerApp
import io.github.linuskromnow.linusexplorer.R
import io.github.linuskromnow.linusexplorer.fs.VfsPaths
import io.github.linuskromnow.linusexplorer.fs.trash.RecycleBin
import io.github.linuskromnow.linusexplorer.fs.trash.TrashedItem
import io.github.linuskromnow.linusexplorer.ui.browser.BrowserEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecycleBinUiState(
    val items: List<TrashedItem> = emptyList(),
    val loading: Boolean = true,
    /** Waiting for the user to confirm deleting this item for good. */
    val confirmDelete: TrashedItem? = null,
    val confirmEmpty: Boolean = false,
)

class RecycleBinViewModel(application: Application) : AndroidViewModel(application) {
    private val bin = (application as LinusExplorerApp).container.recycleBin
    private val _state = MutableStateFlow(RecycleBinUiState())
    val state = _state.asStateFlow()
    private val _events = Channel<BrowserEvent.Message>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        perform {}
    }

    fun restore(item: TrashedItem) = perform {
        val path = bin.restore(item)
        _events.trySend(BrowserEvent.Message(R.string.msg_restored, listOf(VfsPaths.parent(path) ?: path)))
    }

    fun askDelete(item: TrashedItem?) = _state.update { it.copy(confirmDelete = item) }

    fun askEmpty(ask: Boolean) = _state.update { it.copy(confirmEmpty = ask) }

    fun delete(item: TrashedItem) = perform { bin.delete(item) }

    fun empty() = perform { bin.empty() }

    /** Runs [action], then lists the bin again; errors become a snackbar. */
    private fun perform(action: suspend () -> Unit) {
        _state.update { it.copy(loading = true, confirmDelete = null, confirmEmpty = false) }
        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.trySend(BrowserEvent.Message(R.string.msg_error, listOf(e.message ?: e.javaClass.simpleName)))
            }
            val items = try {
                bin.list()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _state.update { it.copy(items = items, loading = false) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleBinScreen(onBack: () -> Unit, viewModel: RecycleBinViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { launch { snackbarHostState.showSnackbar(it.resolve(resources)) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recycle_bin_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (state.items.isNotEmpty()) {
                        IconButton(onClick = { viewModel.askEmpty(true) }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.action_empty_bin))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading && state.items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.items.isEmpty() -> Text(
                    pluralStringResource(R.plurals.recycle_bin_empty, RecycleBin.RETENTION_DAYS, RecycleBin.RETENTION_DAYS),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.items, key = { it.id }) { item ->
                        TrashedRow(item, onRestore = { viewModel.restore(item) }, onDelete = { viewModel.askDelete(item) })
                    }
                }
            }
        }
    }

    state.confirmDelete?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.delete_forever_title, item.name),
            onConfirm = { viewModel.delete(item) },
            onDismiss = { viewModel.askDelete(null) },
        )
    }
    if (state.confirmEmpty) {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.empty_bin_title, state.items.size, state.items.size),
            onConfirm = viewModel::empty,
            onDismiss = { viewModel.askEmpty(false) },
        )
    }
}

@Composable
private fun TrashedRow(item: TrashedItem, onRestore: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val deleted = DateUtils.getRelativeTimeSpanString(item.deletedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    val details = listOfNotNull(
        VfsPaths.parent(item.originalPath),
        deleted.toString(),
        Formatter.formatShortFileSize(context, item.size).takeIf { !item.isDirectory },
    ).joinToString(" · ")
    ListItem(
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(details, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Icon(if (item.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null)
        },
        trailingContent = {
            Row {
                IconButton(onClick = onRestore) {
                    Icon(Icons.Default.RestoreFromTrash, contentDescription = stringResource(R.string.action_restore))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.DeleteForever, contentDescription = stringResource(R.string.action_delete_forever))
                }
            }
        },
    )
}

@Composable
private fun ConfirmDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(stringResource(R.string.delete_warning)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

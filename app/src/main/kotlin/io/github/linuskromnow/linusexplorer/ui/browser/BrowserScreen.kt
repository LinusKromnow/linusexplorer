package io.github.linuskromnow.linusexplorer.ui.browser

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linuskromnow.linusexplorer.R
import io.github.linuskromnow.linusexplorer.fs.BrowserPrefs
import io.github.linuskromnow.linusexplorer.fs.FileEncryption
import io.github.linuskromnow.linusexplorer.fs.Location
import io.github.linuskromnow.linusexplorer.fs.SortField
import io.github.linuskromnow.linusexplorer.fs.archive.ArchiveFormat
import io.github.linuskromnow.linusexplorer.fs.local.LocalFileSystem
import io.github.linuskromnow.linusexplorer.security.Biometrics
import io.github.linuskromnow.linusexplorer.security.SecureWindow
import io.github.linuskromnow.linusexplorer.security.VaultBiometrics
import io.github.linuskromnow.linusexplorer.security.findFragmentActivity
import io.github.linuskromnow.linusexplorer.storage.StorageAccess
import io.github.linuskromnow.linusexplorer.ui.lock.AppLockSettingsDialog
import io.github.linuskromnow.linusexplorer.ui.lock.AppLockViewModel
import io.github.linuskromnow.linusexplorer.util.FileOpener
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(viewModel: BrowserViewModel = viewModel(), lockViewModel: AppLockViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lockState by lockViewModel.state.collectAsStateWithLifecycle()
    var showAppLockSettings by rememberSaveable { mutableStateOf(false) }
    SecureWindow(lockState.enabled || state.inVault)
    val vaultPrompt = VaultBiometrics.Prompt(stringResource(R.string.vault_biometric_enable_title), stringResource(R.string.action_cancel))
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var hasAccess by remember { mutableStateOf(StorageAccess.isGranted(context)) }
    fun recheckAccess() {
        val granted = StorageAccess.isGranted(context)
        if (granted != hasAccess) {
            hasAccess = granted
            if (granted) viewModel.refresh()
        }
    }
    LifecycleResumeEffect(Unit) {
        recheckAccess() // the user may have just returned from the All files access settings page
        viewModel.refreshPlaces()
        onPauseOrDispose { }
    }
    LifecycleStartEffect(Unit) {
        viewModel.onAppForeground()
        onStopOrDispose { viewModel.onAppBackground() }
    }
    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { recheckAccess() }
    val requestAccess = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            StorageAccess.openAllFilesAccessSettings(context)
        } else {
            legacyPermissionLauncher.launch(StorageAccess.legacyPermissions)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BrowserEvent.Message -> launch { snackbarHostState.showSnackbar(event.resolve(resources)) }
                is BrowserEvent.OpenFile -> if (!FileOpener.open(context, event.path)) {
                    launch { snackbarHostState.showSnackbar(resources.getString(R.string.msg_no_app_to_open)) }
                }
                is BrowserEvent.Share -> FileOpener.share(context, event.paths)
            }
        }
    }

    BackHandler(enabled = state.inSelectionMode || state.canGoBack) {
        if (state.inSelectionMode) viewModel.clearSelection() else viewModel.navigateBack()
    }
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            PlacesDrawer(
                places = state.places,
                currentPath = state.location.takeIf { it.fsId == LocalFileSystem.ID }?.path,
                vaultSelected = state.inVault,
                onPlaceClick = { place ->
                    viewModel.navigateTo(Location(LocalFileSystem.ID, place.path))
                    scope.launch { drawerState.close() }
                },
                onVaultClick = {
                    viewModel.openVault()
                    scope.launch { drawerState.close() }
                },
                onAppLockClick = {
                    showAppLockSettings = true
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                if (state.inSelectionMode) {
                    SelectionTopBar(state, viewModel)
                } else {
                    BrowserTopBar(
                        title = state.breadcrumbs.lastOrNull()?.label.orEmpty(),
                        prefs = state.prefs,
                        readOnly = state.readOnly,
                        inVault = state.inVault,
                        vaultBiometric = state.vaultBiometric.takeIf { viewModel.vaultBiometrics.isAvailable(context) },
                        onToggleVaultBiometric = {
                            val activity = context.findFragmentActivity()
                            val key = viewModel.vaultMasterKey()
                            if (state.vaultBiometric) {
                                viewModel.vaultBiometrics.disable()
                                viewModel.onVaultBiometricChanged()
                            } else if (activity != null && key != null) {
                                viewModel.vaultBiometrics.enable(activity, key, vaultPrompt) { viewModel.onVaultBiometricChanged() }
                            }
                        },
                        onMenuClick = { scope.launch { drawerState.open() } },
                        viewModel = viewModel,
                    )
                }
            },
            floatingActionButton = {
                if (hasAccess && !state.inSelectionMode && !state.readOnly) BrowserFab(state, viewModel)
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                if (!hasAccess) {
                    PermissionContent(onGrantClick = requestAccess)
                    return@Column
                }
                BreadcrumbBar(state.breadcrumbs, onClick = { viewModel.navigateTo(it.path) })
                val hiddenTransfer = state.transfer?.takeIf { state.transferHidden }
                if (hiddenTransfer != null) {
                    val progress = hiddenTransfer.progress
                    val modifier = Modifier.fillMaxWidth().clickable(onClick = viewModel::showTransfer)
                    if (progress == null) {
                        LinearProgressIndicator(modifier)
                    } else {
                        LinearProgressIndicator(progress = { progress.fraction }, modifier = modifier)
                    }
                }
                HorizontalDivider()
                PullToRefreshBox(
                    isRefreshing = state.isLoading,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.weight(1f),
                ) {
                    when {
                        state.error != null -> MessageContent(
                            text = state.error.orEmpty(),
                            actionLabel = stringResource(R.string.action_retry),
                            onAction = viewModel::refresh,
                        )
                        state.entries.isEmpty() && !state.isLoading -> MessageContent(
                            text = stringResource(R.string.folder_empty),
                        )
                        else -> FileList(state, viewModel)
                    }
                }
            }
        }
    }

    state.dialog?.let { BrowserDialogHost(it, viewModel) }
    if (showAppLockSettings) {
        val close = { showAppLockSettings = false }
        AppLockSettingsDialog(
            state = lockState,
            biometricAvailable = remember { Biometrics.isAvailable(context) },
            onEnable = { pin, biometric -> lockViewModel.enable(pin, biometric).also { close() } },
            onSetBiometric = lockViewModel::setBiometric,
            onDisable = { lockViewModel.disable().also { close() } },
            onDismiss = close,
        )
    }
    state.transfer?.let { transfer ->
        val conflict = transfer.conflict
        when {
            conflict != null -> ConflictDialog(conflict, onResolve = viewModel::resolveConflict, onCancel = viewModel::cancelTransfer)
            !state.transferHidden -> TransferDialog(transfer, onCancel = viewModel::cancelTransfer, onHide = viewModel::hideTransfer)
        }
    }

    // The progress notification needs this permission on Android 13+; ask the first time a task runs.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.transfer != null) {
        if (state.transfer != null && !askedForNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedForNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserTopBar(
    title: String,
    prefs: BrowserPrefs,
    readOnly: Boolean,
    inVault: Boolean,
    /** Fingerprint unlock state for the vault; null when the device has no strong biometrics. */
    vaultBiometric: Boolean?,
    onToggleVaultBiometric: () -> Unit,
    onMenuClick: () -> Unit,
    viewModel: BrowserViewModel,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.action_open_drawer))
            }
        },
        actions = {
            IconButton(onClick = { sortMenuOpen = true }) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.action_sort))
            }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                SortField.entries.forEach { field ->
                    CheckableMenuItem(stringResource(field.labelRes()), prefs.sortField == field) {
                        viewModel.updatePrefs { it.copy(sortField = field) }
                        sortMenuOpen = false
                    }
                }
                HorizontalDivider()
                CheckableMenuItem(stringResource(R.string.sort_descending), prefs.descending) {
                    viewModel.updatePrefs { it.copy(descending = !it.descending) }
                }
                CheckableMenuItem(stringResource(R.string.sort_folders_first), prefs.foldersFirst) {
                    viewModel.updatePrefs { it.copy(foldersFirst = !it.foldersFirst) }
                }
            }
            IconButton(onClick = { overflowOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
            }
            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                CheckableMenuItem(stringResource(R.string.action_show_hidden), prefs.showHidden) {
                    viewModel.updatePrefs { it.copy(showHidden = !it.showHidden) }
                }
                if (!readOnly) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_new_folder)) },
                        onClick = {
                            overflowOpen = false
                            viewModel.showDialog(BrowserDialog.NewFolder)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_new_file)) },
                        onClick = {
                            overflowOpen = false
                            viewModel.showDialog(BrowserDialog.NewFile)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_refresh)) },
                    onClick = {
                        overflowOpen = false
                        viewModel.refresh()
                    },
                )
                if (inVault) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_change_vault_password)) },
                        onClick = {
                            overflowOpen = false
                            viewModel.showDialog(BrowserDialog.ChangeVaultPassword)
                        },
                    )
                    if (vaultBiometric != null) {
                        CheckableMenuItem(stringResource(R.string.vault_biometric), vaultBiometric) {
                            overflowOpen = false
                            onToggleVaultBiometric()
                        }
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_backup_vault)) },
                        onClick = {
                            overflowOpen = false
                            viewModel.backupVault()
                        },
                    )
                }
            }
            if (inVault) {
                IconButton(onClick = viewModel::lockVault) {
                    Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.action_lock_vault))
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(state: BrowserUiState, viewModel: BrowserViewModel) {
    var overflowOpen by remember { mutableStateOf(false) }
    val selected = state.selectedEntries
    TopAppBar(
        title = { Text(pluralStringResource(R.plurals.selected_count, selected.size, selected.size)) },
        navigationIcon = {
            IconButton(onClick = viewModel::clearSelection) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_clear_selection))
            }
        },
        actions = {
            IconButton(onClick = { viewModel.copySelection(cut = false) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy))
            }
            if (!state.readOnly) {
                IconButton(onClick = { viewModel.copySelection(cut = true) }) {
                    Icon(Icons.Default.ContentCut, contentDescription = stringResource(R.string.action_cut))
                }
                IconButton(onClick = viewModel::requestDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                }
            }
            IconButton(onClick = { overflowOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
            }
            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                val single = selected.singleOrNull()
                val isLocal = state.location.fsId == LocalFileSystem.ID
                val menuItems = buildList {
                    if (!state.readOnly) {
                        add(MenuAction(R.string.action_rename, enabled = single != null, viewModel::requestRename))
                        add(MenuAction(R.string.action_compress, action = viewModel::requestCompress))
                        if (single != null && FileEncryption.isEncrypted(single.name)) {
                            add(MenuAction(R.string.action_decrypt, action = viewModel::requestDecrypt))
                        } else {
                            add(MenuAction(R.string.action_encrypt, enabled = selected.none { it.isDirectory }, viewModel::requestEncrypt))
                        }
                        if (!state.inVault) add(MenuAction(R.string.action_move_to_vault, action = viewModel::moveSelectionToVault))
                    }
                    if (isLocal && single != null && ArchiveFormat.detect(single.name) != null) {
                        add(MenuAction(R.string.action_extract, action = viewModel::extractSelection))
                    }
                    if (single != null && !single.isDirectory) {
                        add(MenuAction(R.string.action_open_with, action = viewModel::openSelectionExternally))
                    }
                    if (isLocal) {
                        add(MenuAction(R.string.action_share, enabled = selected.none { it.isDirectory }, viewModel::shareSelection))
                    }
                }
                menuItems.forEach { (label, enabled, action) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        enabled = enabled,
                        onClick = {
                            overflowOpen = false
                            action()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_select_all)) },
                    onClick = {
                        overflowOpen = false
                        viewModel.selectAll()
                    },
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    )
}

private data class MenuAction(val label: Int, val enabled: Boolean = true, val action: () -> Unit)

@Composable
private fun BrowserFab(state: BrowserUiState, viewModel: BrowserViewModel) {
    val clipboard = state.clipboard
    if (clipboard == null) {
        FloatingActionButton(onClick = { viewModel.showDialog(BrowserDialog.NewFolder) }) {
            Icon(Icons.Default.CreateNewFolder, contentDescription = stringResource(R.string.action_new_folder))
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        SmallFloatingActionButton(onClick = viewModel::clearClipboard) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_clear_clipboard))
        }
        ExtendedFloatingActionButton(
            onClick = viewModel::paste,
            icon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
            text = {
                val count = clipboard.items.size
                val label = if (clipboard.isCut) R.plurals.action_move_here else R.plurals.action_paste_here
                Text(pluralStringResource(label, count, count))
            },
        )
    }
}

@Composable
private fun CheckableMenuItem(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = { if (checked) Icon(Icons.Default.Check, contentDescription = null) },
    )
}

private fun SortField.labelRes(): Int = when (this) {
    SortField.NAME -> R.string.sort_name
    SortField.SIZE -> R.string.sort_size
    SortField.DATE -> R.string.sort_date
    SortField.TYPE -> R.string.sort_type
}

package io.github.linuskromnow.linusexplorer.ui.settings

import android.app.Application
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linuskromnow.linusexplorer.LinusExplorerApp
import io.github.linuskromnow.linusexplorer.R
import io.github.linuskromnow.linusexplorer.data.AppearancePrefs
import io.github.linuskromnow.linusexplorer.data.ThemeMode
import io.github.linuskromnow.linusexplorer.fs.BrowserPrefs
import io.github.linuskromnow.linusexplorer.fs.ViewMode
import io.github.linuskromnow.linusexplorer.security.Biometrics
import io.github.linuskromnow.linusexplorer.ui.lock.AppLockSettingsDialog
import io.github.linuskromnow.linusexplorer.ui.lock.AppLockViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = (application as LinusExplorerApp).container.preferences
    val browser = preferences.browser.stateIn(viewModelScope, SharingStarted.Eagerly, BrowserPrefs())
    val appearance = preferences.appearance.stateIn(viewModelScope, SharingStarted.Eagerly, AppearancePrefs())

    fun updateBrowser(transform: (BrowserPrefs) -> BrowserPrefs) {
        viewModelScope.launch { preferences.updateBrowser(transform) }
    }

    fun updateAppearance(transform: (AppearancePrefs) -> AppearancePrefs) {
        viewModelScope.launch { preferences.updateAppearance(transform) }
    }
}

const val SOURCE_CODE_URL = "https://github.com/LinusKromnow/linusexplorer"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
    lockViewModel: AppLockViewModel = viewModel(),
) {
    val browser by viewModel.browser.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val lock by lockViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var themeDialog by rememberSaveable { mutableStateOf(false) }
    var appLockDialog by rememberSaveable { mutableStateOf(false) }
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Section(stringResource(R.string.settings_appearance))
            Row(stringResource(R.string.settings_theme), stringResource(appearance.theme.labelRes())) { themeDialog = true }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow(stringResource(R.string.settings_dynamic_color), stringResource(R.string.settings_dynamic_color_summary), appearance.dynamicColor) {
                    viewModel.updateAppearance { it.copy(dynamicColor = !it.dynamicColor) }
                }
            }
            SwitchRow(stringResource(R.string.settings_grid_view), null, browser.viewMode == ViewMode.GRID) {
                viewModel.updateBrowser { it.copy(viewMode = if (it.viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID) }
            }
            SwitchRow(stringResource(R.string.settings_thumbnails), stringResource(R.string.settings_thumbnails_summary), browser.showThumbnails) {
                viewModel.updateBrowser { it.copy(showThumbnails = !it.showThumbnails) }
            }

            Section(stringResource(R.string.settings_files))
            SwitchRow(stringResource(R.string.action_show_hidden), null, browser.showHidden) {
                viewModel.updateBrowser { it.copy(showHidden = !it.showHidden) }
            }
            SwitchRow(stringResource(R.string.sort_folders_first), null, browser.foldersFirst) {
                viewModel.updateBrowser { it.copy(foldersFirst = !it.foldersFirst) }
            }
            SwitchRow(stringResource(R.string.settings_recycle_bin), stringResource(R.string.settings_recycle_bin_summary), browser.recycleBin) {
                viewModel.updateBrowser { it.copy(recycleBin = !it.recycleBin) }
            }
            SwitchRow(stringResource(R.string.settings_root_access), stringResource(R.string.settings_root_access_summary), browser.rootAccess) {
                viewModel.updateBrowser { it.copy(rootAccess = !it.rootAccess) }
            }

            Section(stringResource(R.string.settings_security))
            Row(
                stringResource(R.string.app_lock_title),
                stringResource(if (lock.enabled) R.string.settings_on else R.string.settings_off),
            ) { appLockDialog = true }

            Section(stringResource(R.string.settings_about))
            Row(stringResource(R.string.app_name), stringResource(R.string.settings_version, version)) {}
            Row(stringResource(R.string.settings_source_code), SOURCE_CODE_URL) {
                context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_CODE_URL.toUri()))
            }
            Row(stringResource(R.string.settings_license), stringResource(R.string.settings_license_summary)) {}
            Row(stringResource(R.string.settings_privacy), stringResource(R.string.settings_privacy_summary)) {}
        }
    }

    if (themeDialog) {
        AlertDialog(
            onDismissRequest = { themeDialog = false },
            title = { Text(stringResource(R.string.settings_theme)) },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        ListItem(
                            headlineContent = { Text(stringResource(mode.labelRes())) },
                            leadingContent = { RadioButton(selected = appearance.theme == mode, onClick = null) },
                            modifier = Modifier.selectable(selected = appearance.theme == mode, role = Role.RadioButton) {
                                viewModel.updateAppearance { it.copy(theme = mode) }
                                themeDialog = false
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { themeDialog = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
    if (appLockDialog) {
        val close = { appLockDialog = false }
        AppLockSettingsDialog(
            state = lock,
            biometricAvailable = remember { Biometrics.isAvailable(context) },
            onEnable = { pin, biometric -> lockViewModel.enable(pin, biometric).also { close() } },
            onSetBiometric = lockViewModel::setBiometric,
            onDisable = { lockViewModel.disable().also { close() } },
            onDismiss = close,
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun Row(title: String, summary: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SwitchRow(title: String, summary: String?, checked: Boolean, onToggle: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.clickable(role = Role.Switch, onClick = onToggle),
    )
}

private fun ThemeMode.labelRes() = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

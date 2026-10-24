package io.github.linuskromnow.linusexplorer

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linuskromnow.linusexplorer.data.AppearancePrefs
import io.github.linuskromnow.linusexplorer.data.ThemeMode
import io.github.linuskromnow.linusexplorer.fs.Location
import io.github.linuskromnow.linusexplorer.security.Biometrics
import io.github.linuskromnow.linusexplorer.security.SecureWindow
import io.github.linuskromnow.linusexplorer.security.findFragmentActivity
import io.github.linuskromnow.linusexplorer.ui.browser.BrowserScreen
import io.github.linuskromnow.linusexplorer.ui.editor.EditorScreen
import io.github.linuskromnow.linusexplorer.ui.lock.AppLockViewModel
import io.github.linuskromnow.linusexplorer.ui.lock.LockScreen
import io.github.linuskromnow.linusexplorer.ui.settings.SettingsScreen
import io.github.linuskromnow.linusexplorer.ui.theme.LinusExplorerTheme

// FragmentActivity (a ComponentActivity) because BiometricPrompt needs one.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val preferences = (application as LinusExplorerApp).container.preferences
        setContent {
            val appearance by preferences.appearance.collectAsStateWithLifecycle(AppearancePrefs())
            val dark = when (appearance.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            DisposableEffect(dark) {
                // Status/navigation bar icons follow the app theme, not only the system one.
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(style, style)
                onDispose { }
            }
            LinusExplorerTheme(darkTheme = dark, dynamicColor = appearance.dynamicColor) {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot(lockViewModel: AppLockViewModel = viewModel()) {
    val lock by lockViewModel.state.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        lockViewModel.onAppForeground()
        onStopOrDispose { lockViewModel.onAppBackground() }
    }
    when {
        !lock.loaded -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        lock.locked -> {
            SecureWindow(true)
            val activity = LocalContext.current.findFragmentActivity()
            val title = stringResource(R.string.lock_biometric_title)
            val negative = stringResource(R.string.lock_biometric_negative)
            val biometric = if (lock.biometric && activity != null && Biometrics.isAvailable(activity)) {
                { Biometrics.authenticate(activity, title, negative, lockViewModel::onBiometricSuccess) }
            } else {
                null
            }
            LockScreen(
                state = lock,
                onSubmit = lockViewModel::submitPin,
                onInput = lockViewModel::clearWrongPin,
                onBiometric = biometric,
            )
        }
        showSettings -> {
            BackHandler { showSettings = false }
            SecureWindow(lock.enabled)
            SettingsScreen(onBack = { showSettings = false }, lockViewModel = lockViewModel)
        }
        else -> {
            // The editor is drawn over the browser instead of replacing it, so browser state and
            // the vault's background auto-lock keep working while a file is open.
            var editing by rememberSaveable(stateSaver = LocationSaver) { mutableStateOf<Location?>(null) }
            BrowserScreen(
                onEditFile = { editing = it },
                onOpenSettings = { showSettings = true },
                lockViewModel = lockViewModel,
            )
            editing?.let { EditorScreen(location = it, onClose = { editing = null }) }
        }
    }
}

private val LocationSaver = listSaver<Location?, String>(
    save = { location -> listOfNotNull(location?.fsId, location?.path) },
    restore = { saved -> saved.takeIf { it.size == 2 }?.let { Location(it[0], it[1]) } },
)

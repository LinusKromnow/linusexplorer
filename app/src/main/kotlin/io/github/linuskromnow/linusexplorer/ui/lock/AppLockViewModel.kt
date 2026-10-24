package io.github.linuskromnow.linusexplorer.ui.lock

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.linuskromnow.linusexplorer.LinusExplorerApp
import io.github.linuskromnow.linusexplorer.data.AppLockPrefs
import io.github.linuskromnow.linusexplorer.security.LockoutPolicy
import io.github.linuskromnow.linusexplorer.security.PinHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppLockUiState(
    /** False until the stored settings were read; nothing is shown before that to avoid a flash of content. */
    val loaded: Boolean = false,
    val enabled: Boolean = false,
    val locked: Boolean = false,
    val biometric: Boolean = false,
    val lockedUntil: Long = 0,
    val wrongPin: Boolean = false,
    val checking: Boolean = false,
)

class AppLockViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = (application as LinusExplorerApp).container.preferences

    private val _state = MutableStateFlow(AppLockUiState())
    val state: StateFlow<AppLockUiState> = _state.asStateFlow()

    private var backgroundSince: Long? = null

    init {
        viewModelScope.launch {
            preferences.appLock.collect { prefs ->
                _state.update {
                    it.copy(
                        loaded = true,
                        enabled = prefs.enabled,
                        biometric = prefs.enabled && prefs.biometric,
                        lockedUntil = prefs.lockedUntil,
                        // Lock at app start; turning the lock on while using the app doesn't lock it.
                        locked = prefs.enabled && (!it.loaded || it.locked),
                    )
                }
            }
        }
    }

    fun submitPin(pin: String) {
        val now = System.currentTimeMillis()
        if (_state.value.checking || now < _state.value.lockedUntil) return
        _state.update { it.copy(checking = true) }
        viewModelScope.launch {
            val prefs = preferences.appLock.first()
            val hash = prefs.pinHash ?: return@launch unlock()
            val correct = withContext(Dispatchers.Default) { PinHasher.verify(pin, hash) }
            if (correct) {
                unlock()
            } else {
                val failures = prefs.failures + 1
                preferences.updateAppLock {
                    it.copy(failures = failures, lockedUntil = System.currentTimeMillis() + LockoutPolicy.delayAfter(failures))
                }
                _state.update { it.copy(checking = false, wrongPin = true) }
            }
        }
    }

    fun onBiometricSuccess() {
        viewModelScope.launch { unlock() }
    }

    fun clearWrongPin() {
        _state.update { it.copy(wrongPin = false) }
    }

    fun onAppBackground() {
        backgroundSince = System.currentTimeMillis()
    }

    fun onAppForeground() {
        val since = backgroundSince ?: return
        backgroundSince = null
        if (_state.value.enabled && System.currentTimeMillis() - since >= RELOCK_AFTER_MS) {
            _state.update { it.copy(locked = true) }
        }
    }

    fun enable(pin: String, biometric: Boolean) {
        viewModelScope.launch {
            val hash = withContext(Dispatchers.Default) { PinHasher.hash(pin) }
            preferences.updateAppLock { AppLockPrefs(pinHash = hash, biometric = biometric) }
        }
    }

    fun setBiometric(enabled: Boolean) {
        viewModelScope.launch { preferences.updateAppLock { it.copy(biometric = enabled) } }
    }

    fun disable() {
        viewModelScope.launch { preferences.updateAppLock { AppLockPrefs() } }
    }

    private suspend fun unlock() {
        preferences.updateAppLock { it.copy(failures = 0, lockedUntil = 0) }
        _state.update { it.copy(locked = false, checking = false, wrongPin = false) }
    }

    private companion object {
        const val RELOCK_AFTER_MS = 30_000L
    }
}

package io.github.linuskromnow.linusexplorer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.linuskromnow.linusexplorer.fs.BrowserPrefs
import io.github.linuskromnow.linusexplorer.fs.SortField
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppLockPrefs(
    /** [io.github.linuskromnow.linusexplorer.security.PinHasher] hash; null when app lock is off. */
    val pinHash: String? = null,
    val biometric: Boolean = false,
    /** Consecutive wrong PINs, persisted so force-stopping the app doesn't reset the lockout. */
    val failures: Int = 0,
    val lockedUntil: Long = 0,
) {
    val enabled: Boolean get() = pinHash != null
}

class UserPreferences(context: Context) {
    private val store = context.applicationContext.dataStore

    val browser: Flow<BrowserPrefs> = store.data.map { it.toBrowserPrefs() }

    val appLock: Flow<AppLockPrefs> = store.data.map { it.toAppLockPrefs() }

    suspend fun updateBrowser(transform: (BrowserPrefs) -> BrowserPrefs) {
        store.edit { prefs ->
            val updated = transform(prefs.toBrowserPrefs())
            prefs[SORT_FIELD] = updated.sortField.name
            prefs[DESCENDING] = updated.descending
            prefs[FOLDERS_FIRST] = updated.foldersFirst
            prefs[SHOW_HIDDEN] = updated.showHidden
        }
    }

    suspend fun updateAppLock(transform: (AppLockPrefs) -> AppLockPrefs) {
        store.edit { prefs ->
            val updated = transform(prefs.toAppLockPrefs())
            if (updated.pinHash != null) prefs[PIN_HASH] = updated.pinHash else prefs.remove(PIN_HASH)
            prefs[BIOMETRIC] = updated.biometric
            prefs[FAILURES] = updated.failures
            prefs[LOCKED_UNTIL] = updated.lockedUntil
        }
    }

    private fun Preferences.toBrowserPrefs(): BrowserPrefs {
        val defaults = BrowserPrefs()
        return BrowserPrefs(
            sortField = this[SORT_FIELD]?.let { name -> SortField.entries.find { it.name == name } } ?: defaults.sortField,
            descending = this[DESCENDING] ?: defaults.descending,
            foldersFirst = this[FOLDERS_FIRST] ?: defaults.foldersFirst,
            showHidden = this[SHOW_HIDDEN] ?: defaults.showHidden,
        )
    }

    private fun Preferences.toAppLockPrefs() = AppLockPrefs(
        pinHash = this[PIN_HASH],
        biometric = this[BIOMETRIC] ?: false,
        failures = this[FAILURES] ?: 0,
        lockedUntil = this[LOCKED_UNTIL] ?: 0,
    )

    private companion object {
        val SORT_FIELD = stringPreferencesKey("sort_field")
        val DESCENDING = booleanPreferencesKey("sort_descending")
        val FOLDERS_FIRST = booleanPreferencesKey("folders_first")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        val PIN_HASH = stringPreferencesKey("app_lock_pin_hash")
        val BIOMETRIC = booleanPreferencesKey("app_lock_biometric")
        val FAILURES = intPreferencesKey("app_lock_failures")
        val LOCKED_UNTIL = longPreferencesKey("app_lock_locked_until")
    }
}

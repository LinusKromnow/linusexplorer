package io.github.linuskromnow.linusexplorer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.linuskromnow.linusexplorer.fs.BrowserPrefs
import io.github.linuskromnow.linusexplorer.fs.SortField
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class UserPreferences(context: Context) {
    private val store = context.applicationContext.dataStore

    val browser: Flow<BrowserPrefs> = store.data.map { it.toBrowserPrefs() }

    suspend fun updateBrowser(transform: (BrowserPrefs) -> BrowserPrefs) {
        store.edit { prefs ->
            val updated = transform(prefs.toBrowserPrefs())
            prefs[SORT_FIELD] = updated.sortField.name
            prefs[DESCENDING] = updated.descending
            prefs[FOLDERS_FIRST] = updated.foldersFirst
            prefs[SHOW_HIDDEN] = updated.showHidden
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

    private companion object {
        val SORT_FIELD = stringPreferencesKey("sort_field")
        val DESCENDING = booleanPreferencesKey("sort_descending")
        val FOLDERS_FIRST = booleanPreferencesKey("folders_first")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
    }
}

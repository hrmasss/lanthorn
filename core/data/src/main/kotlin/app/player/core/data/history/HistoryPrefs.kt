package app.player.core.data.history

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.historyStore: DataStore<Preferences> by preferencesDataStore("history")

/**
 * "Pause history" (US-73.2, default off). Anything that records history (resume points, last
 * played marks, search and URL history) checks [isPaused] before writing.
 */
class HistoryPrefs(context: Context) {
    private val store = context.applicationContext.historyStore

    val paused: Flow<Boolean> = store.data.map { it[PAUSED] ?: false }

    suspend fun isPaused(): Boolean = paused.first()

    suspend fun setPaused(paused: Boolean) {
        store.edit { it[PAUSED] = paused }
    }

    private companion object {
        val PAUSED = booleanPreferencesKey("paused")
    }
}

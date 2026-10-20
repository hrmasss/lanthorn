package app.player.feature.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.playbackStore: DataStore<Preferences> by preferencesDataStore("playback")

/** The Playback settings group's values. */
data class PlaybackSettings(
    val resume: ResumeMode = ResumeMode.Yes,
    val resumeFirstOnly: Boolean = false,
    val openLinks: Boolean = true,
)

class PlaybackPrefs(context: Context) {
    val store = context.applicationContext.playbackStore

    val settings: Flow<PlaybackSettings> = store.data.map { p ->
        PlaybackSettings(
            resume = p[RESUME]?.let { v -> ResumeMode.entries.firstOrNull { it.name == v } } ?: ResumeMode.Yes,
            resumeFirstOnly = p[RESUME_FIRST_ONLY] ?: false,
            openLinks = p[OPEN_LINKS] ?: true,
        )
    }

    suspend fun setResume(mode: ResumeMode) = store.edit { it[RESUME] = mode.name }
    suspend fun setResumeFirstOnly(on: Boolean) = store.edit { it[RESUME_FIRST_ONLY] = on }
    suspend fun setOpenLinks(on: Boolean) = store.edit { it[OPEN_LINKS] = on }
    suspend fun reset() = store.edit { it.clear() }

    companion object {
        val RESUME = stringPreferencesKey("resume")
        val RESUME_FIRST_ONLY = booleanPreferencesKey("resume_first_only")
        val OPEN_LINKS = booleanPreferencesKey("open_links")
    }
}

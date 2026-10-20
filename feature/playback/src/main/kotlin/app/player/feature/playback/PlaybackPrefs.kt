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
    val loop: LoopMode = LoopMode.Off,
    val shuffle: Boolean = false,
    /** Close the player when the queue ends (US-24.5). */
    val backToList: Boolean = false,
    val smartPrevious: Boolean = true,
    val stepButtons: Boolean = false,
    val showPreviousNext: Boolean = true,
    val skipFailed: Boolean = false,
)

class PlaybackPrefs(context: Context) {
    val store = context.applicationContext.playbackStore

    val settings: Flow<PlaybackSettings> = store.data.map { p ->
        PlaybackSettings(
            resume = p[RESUME]?.let { v -> ResumeMode.entries.firstOrNull { it.name == v } } ?: ResumeMode.Yes,
            resumeFirstOnly = p[RESUME_FIRST_ONLY] ?: false,
            openLinks = p[OPEN_LINKS] ?: true,
            loop = p[LOOP]?.let { v -> LoopMode.entries.firstOrNull { it.name == v } } ?: LoopMode.Off,
            shuffle = p[SHUFFLE] ?: false,
            backToList = p[BACK_TO_LIST] ?: false,
            smartPrevious = p[SMART_PREVIOUS] ?: true,
            stepButtons = p[STEP_BUTTONS] ?: false,
            showPreviousNext = p[SHOW_PREVIOUS_NEXT] ?: true,
            skipFailed = p[SKIP_FAILED] ?: false,
        )
    }

    suspend fun setResume(mode: ResumeMode) = store.edit { it[RESUME] = mode.name }
    suspend fun setResumeFirstOnly(on: Boolean) = store.edit { it[RESUME_FIRST_ONLY] = on }
    suspend fun setOpenLinks(on: Boolean) = store.edit { it[OPEN_LINKS] = on }
    suspend fun setLoop(mode: LoopMode) = store.edit { it[LOOP] = mode.name }
    suspend fun setShuffle(on: Boolean) = store.edit { it[SHUFFLE] = on }
    suspend fun setBackToList(on: Boolean) = store.edit { it[BACK_TO_LIST] = on }
    suspend fun setSmartPrevious(on: Boolean) = store.edit { it[SMART_PREVIOUS] = on }
    suspend fun setStepButtons(on: Boolean) = store.edit { it[STEP_BUTTONS] = on }
    suspend fun setShowPreviousNext(on: Boolean) = store.edit { it[SHOW_PREVIOUS_NEXT] = on }
    suspend fun setSkipFailed(on: Boolean) = store.edit { it[SKIP_FAILED] = on }
    suspend fun reset() = store.edit { it.clear() }

    companion object {
        val RESUME = stringPreferencesKey("resume")
        val RESUME_FIRST_ONLY = booleanPreferencesKey("resume_first_only")
        val OPEN_LINKS = booleanPreferencesKey("open_links")
        val LOOP = stringPreferencesKey("loop")
        val SHUFFLE = booleanPreferencesKey("shuffle")
        val BACK_TO_LIST = booleanPreferencesKey("back_to_list")
        val SMART_PREVIOUS = booleanPreferencesKey("smart_previous")
        val STEP_BUTTONS = booleanPreferencesKey("step_buttons")
        val SHOW_PREVIOUS_NEXT = booleanPreferencesKey("show_previous_next")
        val SKIP_FAILED = booleanPreferencesKey("skip_failed")
    }
}

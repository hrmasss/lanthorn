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
    val lockMode: LockMode = LockMode.Lock,
    val orientation: OrientationMode = OrientationMode.FollowVideo,
    /** "On leaving the app" (US-58.1, US-59.1, US-59.4). */
    val onLeave: OnLeave = OnLeave.Pip,
)

/** Lock (US-28.1) or kids lock (US-28.2). */
enum class LockMode { Lock, Kids }

/** What happens when the viewer leaves the player while it plays. */
enum class OnLeave { Pip, Background, Stop }

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
            lockMode = p[LOCK_MODE].enumOr(LockMode.Lock),
            orientation = p[ORIENTATION].enumOr(OrientationMode.FollowVideo),
            onLeave = p[ON_LEAVE].enumOr(OnLeave.Pip),
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
    suspend fun setLockMode(mode: LockMode) = store.edit { it[LOCK_MODE] = mode.name }
    suspend fun setOrientation(mode: OrientationMode) = store.edit { it[ORIENTATION] = mode.name }
    suspend fun setOnLeave(mode: OnLeave) = store.edit { it[ON_LEAVE] = mode.name }
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
        val LOCK_MODE = stringPreferencesKey("lock_mode")
        val ORIENTATION = stringPreferencesKey("orientation")
        val ON_LEAVE = stringPreferencesKey("on_leave")

        private inline fun <reified E : Enum<E>> String?.enumOr(default: E): E =
            this?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default
    }
}

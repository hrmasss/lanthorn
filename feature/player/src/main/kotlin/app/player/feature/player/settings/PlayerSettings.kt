package app.player.feature.player.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Player settings group. Defaults are the ones the stories state (US-65.2). */
data class PlayerPrefs(
    /** US-16.3, US-79.1. */
    val autoHide: Boolean = true,
    /** US-79.1: 1 s to 10 s in 0.5 s steps. */
    val autoHideDelayMs: Long = 3_000,
    /** US-16.4: the seek row shows remaining time instead of the total. */
    val showRemaining: Boolean = false,
    /** US-60.1. */
    val keepScreenOnPaused: Boolean = false,
    /** US-60.3. */
    val statusInTopBar: Boolean = true,
    /** US-60.4. */
    val keepStatusVisible: Boolean = false,
    /** US-78. */
    val backTwice: Boolean = false,
    /** US-23.2: speed new videos start at, 0.25 to 4.0. */
    val defaultSpeed: Double = 1.0,
) {
    companion object {
        const val MIN_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 10_000L
        const val DELAY_STEP_MS = 500L

        /** Snaps a delay to the allowed range and steps. */
        fun clampDelay(ms: Long): Long =
            ((ms.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS) + DELAY_STEP_MS / 2) / DELAY_STEP_MS) * DELAY_STEP_MS
    }
}

private val Context.playerStore: DataStore<Preferences> by preferencesDataStore("player_settings")

/**
 * Player settings, read once off the main thread and kept in memory. [prefs] holds the defaults
 * until the file is read, so opening the player never waits on disk (NF-2).
 */
class PlayerSettings private constructor(private val store: DataStore<Preferences>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val prefs: StateFlow<PlayerPrefs> = store.data
        .map { it.toPrefs() }
        .stateIn(scope, SharingStarted.Eagerly, PlayerPrefs())

    // One writer at a time, in call order: two quick updates must not land swapped.
    private val writer = Dispatchers.IO.limitedParallelism(1)

    fun update(change: (PlayerPrefs) -> PlayerPrefs) {
        scope.launch(writer) {
            store.edit { p ->
                val next = change(p.toPrefs())
                p[AUTO_HIDE] = next.autoHide
                p[AUTO_HIDE_DELAY] = PlayerPrefs.clampDelay(next.autoHideDelayMs)
                p[SHOW_REMAINING] = next.showRemaining
                p[SCREEN_ON_PAUSED] = next.keepScreenOnPaused
                p[STATUS_TOP_BAR] = next.statusInTopBar
                p[STATUS_KEEP] = next.keepStatusVisible
                p[BACK_TWICE] = next.backTwice
                p[DEFAULT_SPEED] = next.defaultSpeed.coerceIn(0.25, 4.0)
            }
        }
    }

    private fun Preferences.toPrefs(): PlayerPrefs {
        val d = PlayerPrefs()
        return PlayerPrefs(
            autoHide = this[AUTO_HIDE] ?: d.autoHide,
            autoHideDelayMs = this[AUTO_HIDE_DELAY] ?: d.autoHideDelayMs,
            showRemaining = this[SHOW_REMAINING] ?: d.showRemaining,
            keepScreenOnPaused = this[SCREEN_ON_PAUSED] ?: d.keepScreenOnPaused,
            statusInTopBar = this[STATUS_TOP_BAR] ?: d.statusInTopBar,
            keepStatusVisible = this[STATUS_KEEP] ?: d.keepStatusVisible,
            backTwice = this[BACK_TWICE] ?: d.backTwice,
            defaultSpeed = this[DEFAULT_SPEED] ?: d.defaultSpeed,
        )
    }

    companion object {
        private val AUTO_HIDE = booleanPreferencesKey("auto_hide")
        private val AUTO_HIDE_DELAY = longPreferencesKey("auto_hide_delay_ms")
        private val SHOW_REMAINING = booleanPreferencesKey("show_remaining")
        private val SCREEN_ON_PAUSED = booleanPreferencesKey("screen_on_paused")
        private val STATUS_TOP_BAR = booleanPreferencesKey("status_top_bar")
        private val STATUS_KEEP = booleanPreferencesKey("status_keep_visible")
        private val BACK_TWICE = booleanPreferencesKey("back_twice")
        private val DEFAULT_SPEED = doublePreferencesKey("default_speed")

        @Volatile private var instance: PlayerSettings? = null

        fun get(context: Context): PlayerSettings = instance ?: synchronized(this) {
            instance ?: PlayerSettings(context.applicationContext.playerStore).also { instance = it }
        }
    }
}

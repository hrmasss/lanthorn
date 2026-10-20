package app.player.core.engine

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the app's one playback engine (docs/decisions/2026-10-20-engine-lifecycle.md).
 *
 * Holders are the player activity while it is not finishing, and the playback service while it
 * plays in the background or in PiP (P1.7). The engine is created by the first holder and
 * released when the last one lets go, so it outlives activity recreation, backgrounding and the
 * hand-over between activity and service. Main thread only.
 */
object PlayerSessions {
    private var engine: PlayerEngine? = null
    private val holders = mutableSetOf<Any>()
    private val _current = MutableStateFlow<PlayerEngine?>(null)

    /** The live engine, or null when nothing is playing. The media notification follows this. */
    val current: StateFlow<PlayerEngine?> = _current.asStateFlow()

    /**
     * Keep playing when the player leaves the screen (US-59.1): the activity hands the engine to
     * [PlaybackService] instead of pausing. Set from the background-play setting and the player
     * menu toggle (P1.15).
     */
    @Volatile var keepPlayingInBackground: Boolean = false

    /**
     * Joins as a holder and returns the engine, creating it if needed. [onCreated] runs once,
     * right after creation, before any file opens: feature extensions apply saved settings there.
     */
    fun acquire(context: Context, holder: Any, onCreated: (PlayerEngine) -> Unit = {}): PlayerEngine {
        holders += holder
        engine?.let { return it }
        return PlayerEngine(context).also {
            engine = it
            _current.value = it
            onCreated(it)
        }
    }

    /** Leaves. The engine is released when no holder is left. */
    fun release(holder: Any) {
        holders -= holder
        if (holders.isEmpty()) {
            engine?.release()
            engine = null
            _current.value = null
        }
    }
}

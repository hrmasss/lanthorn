package app.player.core.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns the app's one playback engine (docs/decisions/2026-10-20-engine-lifecycle.md).
 *
 * Holders are the player activity while it is not finishing, and the playback service while it
 * plays in the background or in PiP (P1.7). The engine is created by the first holder and
 * released when the last one lets go, so it outlives activity recreation, backgrounding and the
 * hand-over between activity and service. Main thread only.
 *
 * An engine whose decoder stops answering ([PlayerEngine.stalled]) is replaced: a new engine,
 * set up like the first, opens the same file at the same place on SW and becomes [current]. The
 * screen follows [current]; the stuck one is released and left to its thread.
 */
object PlayerSessions {
    private var engine: PlayerEngine? = null
    private val holders = mutableSetOf<Any>()
    private val _current = MutableStateFlow<PlayerEngine?>(null)
    private val scope = MainScope()
    private var setUp: (PlayerEngine) -> Unit = {}
    private var watch: Job? = null

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
        setUp = onCreated
        return start(context.applicationContext)
    }

    private fun start(context: Context): PlayerEngine = PlayerEngine(context).also {
        engine = it
        _current.value = it
        setUp(it)
        watch?.cancel()
        watch = scope.launch {
            val stall = it.stalled.filterNotNull().first()
            if (engine !== it) return@launch
            Log.w(TAG, "replacing the stalled engine: ${stall.uri} at ${stall.positionMs} ms on SW")
            it.release()
            start(context).openAfterStall(stall)
        }
    }

    /** Leaves. The engine is released when no holder is left. */
    fun release(holder: Any) {
        holders -= holder
        if (holders.isEmpty()) {
            watch?.cancel()
            engine?.release()
            engine = null
            _current.value = null
        }
    }

    private const val TAG = "PlayerSessions"
}

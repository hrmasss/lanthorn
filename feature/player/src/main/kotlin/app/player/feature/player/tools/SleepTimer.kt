@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.player.feature.player.tools

import android.os.SystemClock
import app.player.core.engine.PlayerSessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * Sleep timer (US-26). Process-wide, so it keeps running in PiP and in background, and pauses
 * whatever engine [PlayerSessions] holds when it fires.
 */
object SleepTimer {
    /** Null when no timer runs. */
    data class Running(val endsAt: Long, val finishVideo: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val _running = MutableStateFlow<Running?>(null)
    val running: StateFlow<Running?> = _running.asStateFlow()

    /** Starts or replaces the timer. */
    fun start(minutes: Int, finishVideo: Boolean, now: Long = SystemClock.elapsedRealtime()) {
        job?.cancel()
        val r = Running(now + minutes * 60_000L, finishVideo)
        _running.value = r
        job = scope.launch {
            delay(r.endsAt - SystemClock.elapsedRealtime())
            if (r.finishVideo) {
                // US-26.2: let the playing video end, or stop as soon as another one starts.
                val uri = PlayerSessions.current.value?.state?.value?.uri
                PlayerSessions.current.filterNotNull()
                    .flatMapLatest { it.state }
                    .first { it.ended || it.uri != uri }
            }
            PlayerSessions.current.value?.pause()
            _running.value = null
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _running.value = null
    }

    /** Ms left, never negative. */
    fun left(r: Running, now: Long = SystemClock.elapsedRealtime()): Long = (r.endsAt - now).coerceAtLeast(0)

    /** "29:59" style, rounded up to the second so it never shows 0:00 while running. */
    fun format(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    val PRESETS = listOf(15, 30, 45, 60)
}

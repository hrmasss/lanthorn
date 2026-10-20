package app.player.feature.audio

import app.player.core.engine.PropertyType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.roundToLong

/** Audio track and delay wiring on mpv properties (P1.3). Calls return at once. */
internal class AudioController(private val mpv: Mpv) {
    val tracks: Flow<List<Track>> =
        mpv.observe("track-list", PropertyType.Text).map { json -> parseTracks(json).filter { it.type == "audio" } }

    val selected: Flow<Long?> = mpv.observe("aid", PropertyType.Text).map(::parseTrackId)

    val delayMs: Flow<Long?> = mpv.observe("audio-delay", PropertyType.Real).map { it?.let { s -> (s * 1000).roundToLong() } }

    /** US-47.1: mpv switches the audio decoder in place; no seek. */
    fun select(id: Long?) = mpv.set("aid", id?.toString() ?: "no")

    /** US-47.3: an external audio file becomes a track, synced to the video clock by mpv. */
    fun addFile(url: String, title: String?) {
        if (title != null) mpv.command("audio-add", url, "select", title) else mpv.command("audio-add", url, "select")
    }

    /** US-48.1: 10 ms steps, ±10 s. */
    fun setDelayMs(ms: Long) = mpv.set("audio-delay", (snapDelay(ms) / 1000.0).toString())

    companion object {
        const val DELAY_STEP_MS = 10L
        const val MAX_DELAY_MS = 10_000L

        fun snapDelay(ms: Long): Long =
            (Math.round(ms / DELAY_STEP_MS.toDouble()) * DELAY_STEP_MS).coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)
    }
}

package app.player.feature.subtitles

import app.player.core.engine.PropertyType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlin.math.roundToLong

/** Which subtitle tracks are on. Up to two (US-39.3); the primary one is drawn with full ASS styling. */
internal data class SubSelection(val primary: Long? = null, val secondary: Long? = null) {
    /**
     * Tapping a track: on turns it off (the secondary moves up), off turns it on as primary if
     * none is on, else as secondary, replacing the old secondary.
     */
    fun toggle(id: Long): SubSelection = when (id) {
        primary -> SubSelection(primary = secondary)
        secondary -> copy(secondary = null)
        else -> if (primary == null) copy(primary = id) else copy(secondary = id)
    }
}

/**
 * Subtitle wiring on mpv properties (P1.3): tracks, two at once, delay, speed, ASS override,
 * embedded fonts. Every call returns at once; the engine applies them in order on its thread.
 */
internal class SubtitleController(private val mpv: Mpv) {
    private val trackList: StateFlow<String?> = mpv.observe("track-list", PropertyType.Text)
    private val sid: StateFlow<String?> = mpv.observe("sid", PropertyType.Text)
    private val secondarySid: StateFlow<String?> = mpv.observe("secondary-sid", PropertyType.Text)
    private val paused: StateFlow<Boolean?> = mpv.observe("pause", PropertyType.Flag)

    /** Subtitle tracks only, embedded first then external, in mpv's order. */
    val tracks: Flow<List<Track>> = trackList.map { json -> parseTracks(json).filter { it.type == "sub" } }

    val selection: Flow<SubSelection> = combine(sid, secondarySid) { p, s ->
        SubSelection(parseTrackId(p), parseTrackId(s))
    }

    /** Current delay in ms, null until a file is loaded. */
    val delayMs: Flow<Long?> = mpv.observe("sub-delay", PropertyType.Real).map { it?.let { s -> (s * 1000).roundToLong() } }
    val speed: Flow<Double?> = mpv.observe("sub-speed", PropertyType.Real)
    val overrideStyles: Flow<Boolean> = mpv.observe("sub-ass-override", PropertyType.Text).map { it == "strip" }
    val ignoreEmbeddedFonts: Flow<Boolean> = mpv.observe("embeddedfonts", PropertyType.Flag).map { it == false }

    fun select(selection: SubSelection) {
        // mpv refuses the same track in both slots, so clear the secondary before moving tracks.
        mpv.set("secondary-sid", "no")
        mpv.set("sid", selection.primary?.toString() ?: "no")
        mpv.set("secondary-sid", selection.secondary?.toString() ?: "no")
        redrawIfPaused()
    }

    /** Adds a subtitle file mpv can read by path; turns it on unless [select] is false. */
    fun addFile(path: String, title: String?, lang: String? = null, select: Boolean = true) {
        val flag = if (select) "select" else "auto"
        when {
            title != null && lang != null -> mpv.command("sub-add", path, flag, title, lang)
            title != null -> mpv.command("sub-add", path, flag, title)
            else -> mpv.command("sub-add", path, flag)
        }
        if (select) redrawIfPaused()
    }

    /** Re-reads an external track from disk, after its file was rewritten in a new encoding. */
    fun reload(trackId: Long) {
        mpv.command("sub-reload", trackId.toString())
        redrawIfPaused()
    }

    /**
     * mpv redraws a paused frame when a subtitle option changes, but not when the track itself
     * changes (add, reload, select): the screen kept the old line. A zero seek redraws the frame.
     */
    private fun redrawIfPaused() {
        if (paused.value == true) mpv.command("seek", "0", "relative+exact")
    }

    /** US-41.1: 50 ms steps, ±600 s. */
    fun setDelayMs(ms: Long) {
        mpv.set("sub-delay", (snapDelay(ms) / 1000.0).toString())
    }

    /** US-41.2: stretch subtitle timing by 0.5 to 2.0. */
    fun setSpeed(factor: Double) {
        mpv.set("sub-speed", factor.coerceIn(MIN_SPEED, MAX_SPEED).toString())
    }

    /**
     * US-42.4: keep the file's ASS styling ("scale" only applies our size scale), or drop it and
     * draw the text in our style. mpv's "force" keeps inline tags (rotation, italics), so "strip".
     */
    fun setOverrideStyles(override: Boolean) {
        mpv.set("sub-ass-override", if (override) "strip" else "scale")
    }

    /** US-42.4: ignore fonts embedded in the file. mpv reads this when it next loads the file. */
    fun setIgnoreEmbeddedFonts(ignore: Boolean) {
        mpv.set("embeddedfonts", if (ignore) "no" else "yes")
    }

    companion object {
        const val DELAY_STEP_MS = 50L
        const val MAX_DELAY_MS = 600_000L
        const val MIN_SPEED = 0.5
        const val MAX_SPEED = 2.0

        fun snapDelay(ms: Long): Long =
            (Math.round(ms / DELAY_STEP_MS.toDouble()) * DELAY_STEP_MS).coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)
    }
}

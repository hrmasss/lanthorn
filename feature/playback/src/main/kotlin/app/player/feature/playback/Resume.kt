package app.player.feature.playback

import app.player.core.data.PlaybackPosition
import app.player.core.engine.PlaybackState

/** "Resume" setting (US-22.2, US-22.3). */
enum class ResumeMode { Yes, Ask, No }

/** Where a file starts. */
sealed interface StartAt {
    data object Beginning : StartAt

    /** Start at [positionMs] and offer "start over" for 5 s (US-22.2). */
    data class Resume(val positionMs: Long) : StartAt

    /** Ask resume or start over before the first frame (US-22.3). */
    data class Ask(val positionMs: Long) : StartAt
}

/**
 * The resume rules, without Android. The library reads the same rows for its marks (US-07): a
 * finished file keeps its row with position equal to duration, so finished and never played differ.
 */
object Resume {
    /** The last 5% counts as the end (US-22.1, US-07.3). */
    const val FINISHED_SHARE = 0.95

    /** An ended file whose last position update is this close to the end counts as finished. */
    const val ENDED_SLACK_MS = 5_000L

    /** How often a playing file's position is written, so a killed process loses at most this (US-22.5: 10 s). */
    const val SAVE_EVERY_MS = 5_000L

    /** Live protocols: a position in them means nothing on the next open. */
    private val LIVE_SCHEMES = setOf("rtmp", "rtmps", "rtsp", "rtsps", "rtp", "udp", "srt", "mms")

    fun isLiveScheme(uri: String?): Boolean = uri?.substringBefore("://", "")?.lowercase() in LIVE_SCHEMES

    /**
     * The row to write for [state], or null when there is nothing worth saving. Live and unseekable
     * streams get none: mpv says [seekable] false, the protocol is live, or [live] marks a network
     * stream whose duration was unknown when it loaded.
     */
    fun toSave(state: PlaybackState, now: Long, seekable: Boolean? = null, live: Boolean = false): PlaybackPosition? {
        val uri = state.uri ?: return null
        if (!state.loaded || state.error != null || state.durationMs <= 0) return null
        if (seekable == false || live || isLiveScheme(uri)) return null
        // Ended counts only near the end: mpv also reports end of file while it shuts down.
        val finished = state.positionMs >= state.durationMs * FINISHED_SHARE ||
            (state.ended && state.durationMs - state.positionMs <= ENDED_SLACK_MS)
        val position = if (finished) state.durationMs else state.positionMs / 1000 * 1000
        return PlaybackPosition(uri, position.coerceAtLeast(0), state.durationMs, now)
    }

    /** Where to start a file that has [saved] as its resume point. */
    fun startAt(saved: PlaybackPosition?, mode: ResumeMode, laterInQueue: Boolean, firstOnly: Boolean): StartAt {
        if (saved == null || mode == ResumeMode.No) return StartAt.Beginning
        if (isLiveScheme(saved.uri)) return StartAt.Beginning
        if (firstOnly && laterInQueue) return StartAt.Beginning
        if (saved.positionMs < 1000) return StartAt.Beginning
        if (saved.durationMs > 0 && saved.positionMs >= saved.durationMs * FINISHED_SHARE) return StartAt.Beginning
        return if (mode == ResumeMode.Ask) StartAt.Ask(saved.positionMs) else StartAt.Resume(saved.positionMs)
    }
}

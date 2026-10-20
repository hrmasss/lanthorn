package app.player.feature.player

import app.player.core.engine.PlaybackState

/**
 * When the overlay hides by itself (US-16.3, US-69.2, US-79). Returns the delay in ms, or null when
 * the controls must stay up.
 */
internal fun autoHideDelay(
    autoHide: Boolean,
    delayMs: Long,
    paused: Boolean,
    talkBack: Boolean,
    busy: Boolean,
): Long? = when {
    !autoHide -> null
    paused -> null
    // TalkBack users dismiss the controls themselves.
    talkBack -> null
    // A panel or the decoder picker is open, or the viewer is dragging the seek bar.
    busy -> null
    else -> delayMs
}

/**
 * Back twice to leave (US-78). [press] returns true when this press should leave the player, and
 * false when it only arms the 2 s window and the hint should show.
 */
internal class BackTwice(private val windowMs: Long = WINDOW_MS) {
    private var armedAt = Long.MIN_VALUE

    fun press(nowMs: Long): Boolean {
        val leave = armedAt != Long.MIN_VALUE && nowMs - armedAt <= windowMs
        armedAt = if (leave) Long.MIN_VALUE else nowMs
        return leave
    }

    companion object {
        const val WINDOW_MS = 2_000L
    }
}

/** Seek row clock text. Hours appear only when needed. */
internal fun time(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** The right-hand time in the seek row: total, or remaining with a minus (US-16.4). */
internal fun endTime(state: PlaybackState, remaining: Boolean): String =
    if (remaining) "-" + time((state.durationMs - state.positionMs).coerceAtLeast(0)) else time(state.durationMs)

/** "1.5x" for the top bar, or null at normal speed (US-23.3). */
internal fun speedLabel(speed: Double?): String? {
    if (speed == null || kotlin.math.abs(speed - 1.0) < 0.001) return null
    val text = "%.2f".format(java.util.Locale.ROOT, speed).trimEnd('0').trimEnd('.')
    return "${text}x"
}

/** File name without extension, for the title (recon/ux-map.md, Player). */
internal fun titleOf(uri: String?): String {
    if (uri == null) return ""
    val decoded = android.net.Uri.decode(uri)
    val name = decoded.substringAfterLast('/').substringAfterLast(':')
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(0, dot) else name
}

/** Info line for the stream info and the bench: active decoder plus stream basics (NF-5). */
internal fun infoLine(s: PlaybackState): String = buildString {
    append("Decoder ").append(s.activeDecoder.label)
    s.video?.let { append(" · ").append(it.codec).append(' ').append(it.width).append('x').append(it.height) }
    s.video?.takeIf { it.fps > 0 }?.let { append(" · %.2f fps".format(it.fps)) }
    append(" · dropped ").append(s.droppedFrames)
    s.firstFrameMs?.let { append(" · first frame ").append(it).append(" ms") }
}

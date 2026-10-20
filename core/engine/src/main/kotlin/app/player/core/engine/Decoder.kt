package app.player.core.engine

/**
 * What the viewer asks for. Users from other players know the three badges HW, HW+ and SW.
 *
 * - HW: MediaCodec, frames go to the GPU without a CPU copy (mpv `mediacodec`, AImageReader).
 * - HW+: MediaCodec with frames copied back (mpv `mediacodec-copy`), so CPU filters and
 *   deinterlace work.
 * - SW: FFmpeg software decoding.
 *
 * Every mode ends in software decoding if the hardware refuses the stream (NF-5): mpv tries the
 * listed hwdec entries in order at decoder init, then software, and with
 * `vd-lavc-software-fallback=yes` it also drops to software on the first failed frame mid-stream.
 */
enum class DecoderMode(internal val hwdec: String) {
    /** Nothing chosen: HW, then HW+, then SW. */
    AUTO("mediacodec,mediacodec-copy"),

    /** Chosen HW. Falls to HW+, then SW (US-36.2). */
    HW("mediacodec,mediacodec-copy"),

    /** Chosen HW+. Falls to SW. */
    HW_PLUS("mediacodec-copy"),
    SW("no"),
    ;

    /** The decoder this mode is meant to run, or null for AUTO. */
    val intended: ActiveDecoder?
        get() = when (this) {
            AUTO -> null
            HW -> ActiveDecoder.HW
            HW_PLUS -> ActiveDecoder.HW_PLUS
            SW -> ActiveDecoder.SW
        }
}

/** One line for US-35.3, or null when the chosen decoder is the one running (or none was chosen). */
fun fallbackNotice(chosen: DecoderMode, running: ActiveDecoder): String? {
    val intended = chosen.intended ?: return null
    if (running == intended || running == ActiveDecoder.NONE) return null
    return "${intended.label} cannot play this video. Playing with ${running.label}."
}

/**
 * Player features that depend on the decoder (US-35.4). HW frames stay on the GPU, so anything
 * that needs CPU frames, mpv's software video filters, needs HW+ or SW.
 */
enum class DecoderFeature(val needs: Set<ActiveDecoder>) {
    /** `vf` lavfi filters: flips, rotation by filter, night mode, picture filters. */
    VideoFilters(setOf(ActiveDecoder.HW_PLUS, ActiveDecoder.SW)),
    Deinterlace(setOf(ActiveDecoder.HW_PLUS, ActiveDecoder.SW)),
    ;

    fun runsOn(decoder: ActiveDecoder): Boolean = decoder in needs || decoder == ActiveDecoder.NONE

    /** The cheapest decoder that runs it: HW+ before SW. */
    val enabledBy: DecoderMode get() = if (ActiveDecoder.HW_PLUS in needs) DecoderMode.HW_PLUS else DecoderMode.SW
}

/** The decoder actually running, read from mpv's `hwdec-current`. */
enum class ActiveDecoder(val label: String) {
    HW("HW"),
    HW_PLUS("HW+"),
    SW("SW"),

    /** No video stream loaded yet. */
    NONE("-");

    companion object {
        fun fromHwdecCurrent(value: String?): ActiveDecoder = when (value) {
            null, "" -> SW
            "mediacodec" -> HW
            "mediacodec-copy" -> HW_PLUS
            "no" -> SW
            else -> if (value.endsWith("-copy")) HW_PLUS else HW
        }
    }
}

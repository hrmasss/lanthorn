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
    /** HW, then HW+, then SW. The default. */
    AUTO("mediacodec,mediacodec-copy"),
    HW("mediacodec"),
    HW_PLUS("mediacodec-copy"),
    SW("no"),
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

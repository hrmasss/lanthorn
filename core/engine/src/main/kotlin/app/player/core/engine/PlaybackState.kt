package app.player.core.engine

/** One immutable snapshot of the player. Features read this; they never read mpv properties. */
data class PlaybackState(
    val uri: String? = null,
    val loaded: Boolean = false,
    val paused: Boolean = true,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val decoderMode: DecoderMode = DecoderMode.AUTO,
    val activeDecoder: ActiveDecoder = ActiveDecoder.NONE,
    val video: VideoInfo? = null,
    val droppedFrames: Long = 0,
    /** Time from [PlayerEngine.open] to the first frame after load, in ms. Null until shown. */
    val firstFrameMs: Long? = null,
    /** One line when the chosen decoder could not play the stream and another took over (US-35.3). */
    val decoderNotice: String? = null,
    val error: String? = null,
)

data class VideoInfo(
    val codec: String,
    val width: Int,
    val height: Int,
    val fps: Double,
    val pixelFormat: String?,
)

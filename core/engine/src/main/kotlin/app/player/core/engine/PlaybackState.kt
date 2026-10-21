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
    /** Back from the background (US-59.3): ms from the surface coming back to the video showing. */
    val videoBackMs: Long? = null,
    /** Goes up each time playback restarts after a load or a seek: the new frame is ready. */
    val restarts: Int = 0,
    val error: String? = null,
)

/** Frame counters from mpv, read on demand with [PlayerEngine.stats]. */
data class EngineStats(
    /** Frames the video output dropped to keep sync. */
    val voDropped: Long,
    /** Frames the decoder dropped because it fell behind. */
    val decoderDropped: Long,
    val delayed: Long,
    val mistimed: Long,
    /** Frames of the file's video up to the current position. */
    val frameNumber: Long,
    val displayFps: Double,
)

data class VideoInfo(
    val codec: String,
    val width: Int,
    val height: Int,
    val fps: Double,
    val pixelFormat: String?,
)

/** One mpv log line at warning level or worse. [prefix] is mpv's module, for example "stream". */
data class EngineMessage(val prefix: String, val level: Level, val text: String) {
    enum class Level { Warn, Error }
}

/** Where an engine was when its decoder stopped answering ([PlayerEngine.stalled]). */
data class Stall(val uri: String, val positionMs: Long, val paused: Boolean, val decoder: ActiveDecoder)

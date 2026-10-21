package app.player.feature.playback

import app.player.core.engine.EngineMessage

/** Why a file did not play (US-75.1). */
enum class FailureReason { MissingFile, UnsupportedCodec, Damaged, NoPermission }

/** Names the reason from the engine's error and its log lines for this file, without Android. */
object Failures {
    private val missing = listOf("ENOENT", "No such file", "FileNotFoundException", "not found")
    private val permission = listOf("Permission Denial", "EACCES", "Permission denied", "SecurityException")
    private val codec = listOf(
        "Failed to initialize a decoder", "Could not open codec", "No decoder", "codec not found", "Could not find decoder", "Decoder not found",
        "no decoder found", "Requested decoder", "No video or audio streams",
    )

    /**
     * A file that "ends" well before its duration right after mpv logged an error did not play: mpv
     * hit a fatal read error and stopped (US-75.1). A normal end, or a short file, is not this.
     */
    fun endedEarly(positionMs: Long, durationMs: Long, lines: List<EngineMessage>): Boolean =
        durationMs > 0 && positionMs < durationMs * 0.95 && durationMs - positionMs > 1_000 &&
            lines.any { it.level == EngineMessage.Level.Error }

    /** True for a log line that says a decoder could not start. */
    fun isDecoderFailure(m: EngineMessage) = codec.any { m.text.contains(it, ignoreCase = true) }

    fun classify(error: String?, lines: List<EngineMessage>): FailureReason {
        val texts = listOfNotNull(error) + lines.map { it.text }
        fun any(words: List<String>) = texts.any { t -> words.any { t.contains(it, ignoreCase = true) } }
        return when {
            any(permission) -> FailureReason.NoPermission
            any(missing) -> FailureReason.MissingFile
            lines.any(::isDecoderFailure) -> FailureReason.UnsupportedCodec
            else -> FailureReason.Damaged
        }
    }

    /** "Long.mp4" from "primary:Movies/PB/Single/Long.mp4" or "/sdcard/x/Long.mp4"; null for a bare number. */
    fun nameFromPath(segment: String): String? {
        val name = segment.substringAfterLast('/').substringAfterLast(':').trim()
        return name.takeIf { it.isNotEmpty() && !it.all(Char::isDigit) }
    }

    /** The codec mpv could not decode, from its log, for the codec reason's text. */
    fun codecOf(lines: List<EngineMessage>): String? = lines.firstNotNullOfOrNull { m ->
        Regex("""codec '([\w-]+)'|for: ([\w-]+)""").find(m.text)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
    }

    /** Technical lines for "Info": the engine's error and mpv's errors, each a capitalised sentence, no repeats. */
    fun technical(error: String?, lines: List<EngineMessage>): List<String> =
        (listOfNotNull(error) + lines.filter { it.level == EngineMessage.Level.Error }.map { it.text })
            .map { it.trim().removeSuffix(".").replaceFirstChar(Char::uppercase) + "." }
            .distinct()
            .take(6)
}

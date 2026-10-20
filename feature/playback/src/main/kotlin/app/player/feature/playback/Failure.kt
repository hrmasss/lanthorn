package app.player.feature.playback

import app.player.core.engine.EngineMessage

/** Why a file did not play (US-75.1). */
enum class FailureReason { MissingFile, UnsupportedCodec, Damaged, NoPermission }

/** Names the reason from the engine's error and its log lines for this file, without Android. */
object Failures {
    private val missing = listOf("ENOENT", "No such file", "FileNotFoundException", "not found")
    private val permission = listOf("Permission Denial", "EACCES", "Permission denied", "SecurityException")
    private val codec = listOf("Failed to initialize a decoder", "Could not open codec", "No decoder", "codec not found")

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
}

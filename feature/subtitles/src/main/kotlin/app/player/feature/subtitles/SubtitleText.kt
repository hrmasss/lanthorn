package app.player.feature.subtitles

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Turns subtitle file bytes into text (US-44). A byte-order mark, valid UTF-8 or the viewer's own
 * encoding pick is decoded here and handed to mpv as UTF-8. Anything else is left to mpv, whose
 * `sub-codepage=auto` detects it with uchardet and converts with iconv (libmpv P1.33).
 */
internal object SubtitleText {
    /** Formats that are text and may need a charset. VobSub `.sub` is binary; see [isBinary]. */
    private val TEXT_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub", "txt", "smi", "sami", "ttml", "dfxp", "xml", "mpl", "lrc")

    fun isText(extension: String, head: ByteArray): Boolean = extension.lowercase() in TEXT_EXTENSIONS && !isBinary(head)

    /** VobSub `.sub` is an MPEG stream and PGS starts with "PG"; text files have no NUL bytes. */
    fun isBinary(head: ByteArray): Boolean {
        if (head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'G'.code.toByte()) return true
        if (head.size >= 2 && (head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte() || head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte())) {
            return false // UTF-16 text has NULs but a BOM.
        }
        return head.take(4096).any { it == 0.toByte() }
    }

    /**
     * Decodes [bytes]: a byte-order mark wins, then [forced] (the viewer's pick), then strict
     * UTF-8. Null when none applies: the file goes to mpv as it is, for detection.
     */
    fun decode(bytes: ByteArray, forced: Charset? = null): Decoded? {
        bom(bytes)?.let { (charset, skip) ->
            return Decoded(String(bytes, skip, bytes.size - skip, charset), charset)
        }
        if (forced != null) return Decoded(String(bytes, forced), forced)
        strictUtf8(bytes)?.let { return Decoded(it, Charsets.UTF_8) }
        return null
    }

    data class Decoded(val text: String, val charset: Charset)

    private fun bom(b: ByteArray): Pair<Charset, Int>? = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
        else -> null
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /** The encodings the viewer can pick by hand (US-44.2). "Auto" is null. */
    val CHOICES: List<String> = listOf(
        "UTF-8", "UTF-16LE", "UTF-16BE",
        "windows-1250", "windows-1251", "windows-1252", "windows-1253", "windows-1254",
        "windows-1255", "windows-1256", "windows-1257", "windows-1258",
        "ISO-8859-1", "ISO-8859-2", "ISO-8859-5", "ISO-8859-6", "ISO-8859-7", "ISO-8859-8", "ISO-8859-9", "ISO-8859-15",
        "GB18030", "Big5", "Shift_JIS", "EUC-KR", "KOI8-R",
    )
}

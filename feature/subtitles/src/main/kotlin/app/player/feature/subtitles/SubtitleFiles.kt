package app.player.feature.subtitles

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Brings an external subtitle file to mpv. Text formats are decoded here (see [SubtitleText]) and
 * written as UTF-8 to the app cache; binary formats are copied as they are. mpv then reads a
 * plain path, which also works for `content://` files it could not open by name.
 */
internal class SubtitleFiles(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.cacheDir, "subtitles")

    /** Cache file path to where it came from, so a re-encode reads the original bytes. */
    private val sources = ConcurrentHashMap<String, Source>()

    private class Source(val uri: Uri, val extension: String)

    class Prepared(val path: String, val title: String, val charset: Charset?)

    /** Reads [uri], decodes it with [forced] or by detection, and returns a path mpv can load. */
    suspend fun prepare(uri: Uri, forced: Charset? = null, companion: Uri? = null): Prepared = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val extension = name.substringAfterLast('.', "").lowercase()
        val bytes = read(uri)
        dir.mkdirs()
        val out = File(dir, "${digest(uri.toString())}.${extension.ifEmpty { "srt" }}")
        val charset = write(bytes, extension, forced, out)
        // VobSub: mpv opens the .idx and reads the .sub with the same name beside it.
        companion?.let { File(dir, out.nameWithoutExtension + ".sub").writeBytes(read(it)) }
        sources[out.path] = Source(uri, extension)
        Prepared(out.path, name, charset)
    }

    /** Rewrites a prepared file in [charset] (null: detect again). False if the path is not ours. */
    suspend fun reencode(path: String, charset: Charset?): Boolean = withContext(Dispatchers.IO) {
        val source = sources[path] ?: return@withContext false
        write(read(source.uri), source.extension, charset, File(path))
        true
    }

    fun isOurs(path: String?): Boolean = path != null && sources.containsKey(path)

    private fun write(bytes: ByteArray, extension: String, forced: Charset?, out: File): Charset? {
        if (!SubtitleText.isText(extension, bytes.copyOf(minOf(bytes.size, 4096)))) {
            out.writeBytes(bytes)
            return null
        }
        val decoded = SubtitleText.decode(bytes, forced)
        if (decoded == null) {
            // Legacy encoding, not picked by hand: mpv detects it (sub-codepage=auto, uchardet).
            out.writeBytes(bytes)
            return null
        }
        out.writeText(decoded.text, Charsets.UTF_8)
        return decoded.charset
    }

    private fun read(uri: Uri): ByteArray {
        val stream = when (uri.scheme) {
            "content" -> appContext.contentResolver.openInputStream(uri)
            else -> File(uri.path ?: uri.toString()).inputStream()
        } ?: error("cannot open $uri")
        return stream.use { input ->
            val bytes = input.readNBytesCompat(MAX_BYTES + 1)
            require(bytes.size <= MAX_BYTES) { "subtitle file too large" }
            bytes
        }
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "subtitle"
    }


    private fun digest(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    companion object {
        /** Subtitle files are small. Anything larger is not a subtitle and would cost memory. */
        const val MAX_BYTES = 20 * 1024 * 1024
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

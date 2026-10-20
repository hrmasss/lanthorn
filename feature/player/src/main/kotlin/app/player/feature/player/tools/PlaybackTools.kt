package app.player.feature.player.tools

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PropertyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.Locale
import kotlin.math.roundToLong

/**
 * mpv wiring for speed, loop-file, A-B repeat, chapters and frame screenshots (P1.2), through the
 * engine's generic property API only. The menus and buttons that use it come with P1.13.
 *
 * Properties set here, and only here: `speed`, `audio-pitch-correction`, `loop-file`,
 * `ab-loop-a`, `ab-loop-b`, `chapter`, `screenshot-*`.
 */
class PlaybackTools(private val engine: PlayerEngine) {
    /** Runs once per engine: pitch kept at every speed (US-23.1). */
    fun applyDefaults() {
        engine.set("audio-pitch-correction", true)
        engine.set("screenshot-format", "png")
        // Fastest PNG level: a 4K frame still writes well inside US-33.1's 1 s.
        engine.set("screenshot-png-compression", 1L)
        engine.set("screenshot-tag-colorspace", true)
    }

    /** US-23.1: 0.25x to 4.0x in 0.05x steps, pitch kept. */
    fun setSpeed(speed: Double) = engine.set("speed", snapSpeed(speed))

    val speed get() = engine.observe("speed", PropertyType.Real)

    /** US-24.2: loop the current file with no reload between rounds. */
    fun setLoopFile(on: Boolean) = engine.set("loop-file", if (on) "inf" else "no")

    /** US-25.1: point A, then point B; mpv loops B back to A until cleared. */
    fun setA(positionMs: Long) = engine.set("ab-loop-a", seconds(positionMs))
    fun setB(positionMs: Long) = engine.set("ab-loop-b", seconds(positionMs))
    fun clearAb() {
        engine.set("ab-loop-a", "no")
        engine.set("ab-loop-b", "no")
    }

    val abA get() = engine.observe("ab-loop-a", PropertyType.Text)
    val abB get() = engine.observe("ab-loop-b", PropertyType.Text)

    /** US-27: the file's chapters, empty when it has none. */
    suspend fun chapters(): List<Chapter> = parseChapters(engine.get("chapter-list", PropertyType.Text))

    /** Changes whenever a new file loads, so chapter lists can refresh. */
    val chapterCount get() = engine.observe("chapter-list/count", PropertyType.Int64)

    /** US-27.2: seek to a chapter's start. */
    fun goToChapter(index: Int) = engine.set("chapter", index.toLong())

    /**
     * US-33.1: saves the current frame at the video's own size, without subtitles or controls, as a
     * PNG in Pictures/<app name>. Returns its MediaStore uri, or null if mpv could not grab one.
     */
    suspend fun screenshot(context: Context): Uri? {
        val tmp = File(context.cacheDir, "screenshot.png")
        tmp.delete()
        // "video" is the decoded frame alone: no subtitles, no OSD.
        engine.command("screenshot-to-file", tmp.path, "video")
        // Engine calls run in order on one thread and mpv writes the file inside the command, so
        // once this read returns the file is complete.
        engine.get("pause", PropertyType.Flag)
        if (!tmp.exists() || tmp.length() == 0L) return null
        return withContext(Dispatchers.IO) { publish(context, tmp) }
    }

    private fun publish(context: Context, png: File): Uri? {
        val app = context.applicationInfo.loadLabel(context.packageManager).toString()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${app}_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$app")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return runCatching {
            resolver.openOutputStream(uri)!!.use { out -> png.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            png.delete()
            uri
        }.getOrElse {
            resolver.delete(uri, null, null)
            null
        }
    }

    companion object {
        const val MIN_SPEED = 0.25
        const val MAX_SPEED = 4.0
        const val SPEED_STEP = 0.05

        /** Clamps to 0.25..4.0 and rounds to the 0.05 grid. */
        fun snapSpeed(speed: Double): Double =
            ((speed.coerceIn(MIN_SPEED, MAX_SPEED) / SPEED_STEP).roundToLong() * SPEED_STEP * 100).roundToLong() / 100.0

        internal fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.3f", ms / 1000.0)

        /** mpv's `chapter-list` JSON: `[{"title": "...", "time": 12.5}, ...]`. */
        fun parseChapters(json: String?): List<Chapter> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Chapter(i, o.optString("title").takeIf { it.isNotBlank() }, (o.optDouble("time", 0.0) * 1000).roundToLong())
                }
            }.getOrDefault(emptyList())
        }
    }
}

/** A chapter: index, title if the file names it, start in ms. */
data class Chapter(val index: Int, val title: String?, val startMs: Long)

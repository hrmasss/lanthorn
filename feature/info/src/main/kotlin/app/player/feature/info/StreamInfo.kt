package app.player.feature.info

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.player.core.engine.ActiveDecoder
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PropertyType
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Everything US-37 shows. Read from mpv through the generic property API. */
data class StreamInfo(
    val file: List<Pair<String, String>>,
    val video: List<Pair<String, String>>,
    val audio: List<List<Pair<String, String>>>,
    val subtitles: List<List<Pair<String, String>>>,
    val decoder: List<Pair<String, String>>,
    /** Only when opened from the player (US-37.3). */
    val live: List<Pair<String, String>>,
)

internal object StreamInfoReader {
    suspend fun read(context: Context, engine: PlayerEngine): StreamInfo {
        suspend fun text(n: String) = engine.get(n, PropertyType.Text)
        suspend fun long(n: String) = engine.get(n, PropertyType.Int64)
        suspend fun real(n: String) = engine.get(n, PropertyType.Real)

        val uri = engine.state.value.uri
        val path = text("path")
        val file = buildList {
            val name = text("filename")
            add("Name" to (name ?: "-"))
            add("Path" to (path ?: "-"))
            add("Size" to (long("file-size")?.let(::bytes) ?: "-"))
            add("Date" to (uri?.let { modified(context, it) } ?: "-"))
            add("Container" to Names.container(text("file-format"), name))
        }

        val tracks = (0 until (long("track-list/count") ?: 0).toInt()).map { i ->
            val p = "track-list/$i"
            Track(
                type = text("$p/type") ?: "",
                codec = text("$p/codec") ?: "-",
                profile = text("$p/codec-profile"),
                lang = text("$p/lang"),
                title = text("$p/title"),
                external = engine.get("$p/external", PropertyType.Flag) ?: false,
                channels = long("$p/demux-channel-count"),
                sampleRate = long("$p/demux-samplerate"),
                bitrate = long("$p/demux-bitrate"),
            )
        }

        val pixel = text("video-params/pixelformat")
        val hwPixel = text("video-params/hw-pixelformat")
        val videoTrack = tracks.firstOrNull { it.type == "video" }
        val video = if (videoTrack == null) emptyList() else buildList {
            add("Codec" to Names.codec(videoTrack.codec))
            add("Profile" to (videoTrack.profile ?: "-"))
            add("Resolution" to "${long("video-params/w") ?: 0} x ${long("video-params/h") ?: 0}")
            add("Frame rate" to (real("container-fps")?.let { "%.3f fps".format(it) } ?: "-"))
            add("Bit depth" to bitDepth(pixel ?: hwPixel))
            add("Bit rate" to (videoTrack.bitrate?.let(::bitrate) ?: real("video-bitrate")?.let { bitrate(it.toLong()) } ?: "-"))
        }

        val audio = tracks.filter { it.type == "audio" }.map { t ->
            listOf(
                "Codec" to Names.codec(t.codec),
                "Channels" to Names.channels(t.channels),
                "Sample rate" to (t.sampleRate?.let { "$it Hz" } ?: "-"),
                "Language" to (t.lang ?: "-"),
            )
        }
        val subtitles = tracks.filter { it.type == "sub" }.map { t ->
            listOf(
                "Format" to Names.codec(t.codec),
                "Language" to (t.lang ?: "-"),
                "Source" to if (t.external) "External" else "Embedded",
            )
        }

        val hwdec = text("hwdec-current")
        val running = if (videoTrack == null) ActiveDecoder.NONE else ActiveDecoder.fromHwdecCurrent(hwdec)
        val decoder = listOf(
            "Decoder" to running.label,
            "Hardware decoded" to if (running == ActiveDecoder.HW || running == ActiveDecoder.HW_PLUS) "Yes ($hwdec)" else "No",
        )

        val dropped = (long("frame-drop-count") ?: 0) + (long("decoder-frame-drop-count") ?: 0)
        val live = listOf(
            "Position" to "%.1f s".format((real("time-pos") ?: 0.0)),
            "Dropped frames" to dropped.toString(),
            "Current bit rate" to bitrate(((real("video-bitrate") ?: 0.0) + (real("audio-bitrate") ?: 0.0)).toLong()),
        )
        return StreamInfo(file, video, audio, subtitles, decoder, live)
    }

    private data class Track(
        val type: String,
        val codec: String,
        val profile: String?,
        val lang: String?,
        val title: String?,
        val external: Boolean,
        val channels: Long?,
        val sampleRate: Long?,
        val bitrate: Long?,
    )

    fun bitDepth(pixelFormat: String?): String = when {
        pixelFormat == null -> "-"
        pixelFormat.contains("p010") || pixelFormat.contains("10") -> "10-bit"
        pixelFormat.contains("12") -> "12-bit"
        else -> "8-bit"
    }

    fun bytes(n: Long): String = when {
        n >= 1L shl 30 -> "%.2f GB".format(n / (1L shl 30).toDouble())
        n >= 1L shl 20 -> "%.1f MB".format(n / (1L shl 20).toDouble())
        else -> "%.0f KB".format(n / 1024.0)
    }

    fun bitrate(bps: Long): String = if (bps >= 1_000_000) "%.2f Mbps".format(bps / 1e6) else "%.0f kbps".format(bps / 1e3)

    private fun modified(context: Context, uri: String): String? {
        val u = Uri.parse(uri)
        val millis = when (u.scheme) {
            "file", null -> u.path?.let { File(it).lastModified().takeIf { t -> t > 0 } }
            "content" -> runCatching {
                context.contentResolver.query(u, arrayOf("date_modified", OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getLong(0).takeIf { it > 0 }?.times(1000) else null }
            }.getOrNull()
            else -> null
        } ?: return null
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
    }
}

package app.player.feature.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import android.util.LruCache
import android.util.Size
import app.player.core.data.library.LibraryIndex
import app.player.core.data.library.LibraryVideo
import app.player.core.data.library.VideoExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thumbnails for lists (US-02, NF-9). Memory cache first, then the app's disk cache, then a decode.
 * Decodes run two at a time off the main thread and are cancelled when their row scrolls away.
 */
class ThumbnailLoader(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.cacheDir, "thumbs")

    /** Follows the "cache thumbnails" setting (US-02.4). Off: decode every time, keep only the memory cache. */
    @Volatile var cacheOnDisk: Boolean = true

    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val decode = Dispatchers.IO.limitedParallelism(2)

    /** Where thumbnails came from since start, for the perf log (US-02.4). */
    val stats = Stats()

    class Stats {
        val memory = AtomicInteger()
        val disk = AtomicInteger()
        val decoded = AtomicInteger()
        override fun toString() = "memory=${memory.get()} disk=${disk.get()} decoded=${decoded.get()}"
    }

    /** In-memory hit only. Safe on the main thread. */
    fun cached(video: LibraryVideo): Bitmap? = memory.get(video.key)?.also { stats.memory.incrementAndGet() }

    suspend fun load(video: LibraryVideo): Bitmap? {
        cached(video)?.let { return it }
        return withContext(decode) {
            val file = File(dir, "${video.key}.jpg")
            val bitmap = (if (cacheOnDisk) readDisk(file) else null)?.also { stats.disk.incrementAndGet() }
                ?: generate(video.uri.toUri())?.also {
                    stats.decoded.incrementAndGet()
                    if (cacheOnDisk) writeDisk(file, it)
                }
            bitmap?.also { memory.put(video.key, it) }
        }
    }

    /** Deletes cached thumbnails and returns the bytes freed (US-73.3). */
    fun clear(): Long {
        memory.evictAll()
        val files = dir.listFiles().orEmpty()
        val bytes = files.sumOf { it.length() }
        files.forEach { it.delete() }
        return bytes
    }

    private fun readDisk(file: File): Bitmap? = if (file.exists()) BitmapFactory.decodeFile(file.path) else null

    private fun writeDisk(file: File, bitmap: Bitmap) {
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        tmp.renameTo(file)
    }

    /** MediaStore's own thumbnailer first; it is fast and shares the system's cache. A frame grab if it fails. */
    private fun generate(uri: Uri): Bitmap? =
        runCatching { app.contentResolver.loadThumbnail(uri, SIZE, null) }.getOrNull()
            ?: runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(app, uri)
                    r.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE.width, SIZE.height)
                }
            }.getOrNull()

    private val LibraryVideo.key get() = "${id}_$modifiedSec"

    companion object {
        /** 16:9 at three times the grid cell's width in dp, so one size serves list and grid. */
        val SIZE = Size(480, 270)
    }
}

/** Reads details MediaStore does not hold, once per file version, and keeps them in the index. */
class VideoProbe(context: Context, private val index: LibraryIndex) {
    private val app = context.applicationContext

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(2)

    suspend fun fps(video: LibraryVideo): Float = withContext(io) {
        index.extra(video.id)?.takeIf { it.modifiedSec == video.modifiedSec }?.let { return@withContext it.fps }
        val fps = runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(app, video.uri.toUri())
                val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
                val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                if (frames != null && ms != null && ms > 0) frames * 1000f / ms else 0f
            }
        }.getOrDefault(0f)
        index.putExtra(VideoExtra(video.id, video.modifiedSec, fps))
        fps
    }
}

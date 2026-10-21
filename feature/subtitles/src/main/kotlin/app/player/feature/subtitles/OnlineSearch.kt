package app.player.feature.subtitles

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * One search and one download (US-46.1, US-46.2), each started by the viewer's tap (US-46.3).
 * [baseUrl] is the service; tests point it at a local server.
 */
internal class OnlineSearch(context: Context, private val baseUrl: String = OpenSubtitles.BASE_URL) {
    private val appContext = context.applicationContext
    private val accounts = OnlineAccountStore(appContext)

    /** What to search for: the video's file name without extension, and its hash. */
    suspend fun describe(video: Uri): Pair<String, String?> = withContext(Dispatchers.IO) {
        val name = displayName(video)
        name.substringBeforeLast('.') to runCatching { hashOf(video) }.getOrNull()
    }

    suspend fun search(query: String, hash: String?, language: String): List<OpenSubtitles.Result> = withContext(Dispatchers.IO) {
        val account = accounts.current()
        OpenSubtitles(account.apiKey, baseUrl).search(hash, query, language)
    }

    /**
     * Downloads [result] and saves it beside the video when that folder is writable, else in app
     * storage. Returns the saved file, named after the video so it also loads by itself later.
     */
    suspend fun download(video: Uri, result: OpenSubtitles.Result): File = withContext(Dispatchers.IO) {
        val account = accounts.current()
        val client = OpenSubtitles(account.apiKey, baseUrl)
        val token = if (account.username.isNotBlank()) client.login(account.username, account.password) else null
        val file = client.download(token, result.fileId)
        val base = displayName(video).substringBeforeLast('.')
        val ext = file.fileName.substringAfterLast('.', "srt").lowercase()
        val name = "$base.${result.language.ifBlank { "online" }}.$ext"
        val dir = saveDir(video.scheme, video.path, File(appContext.filesDir, "subtitles-online"))
        File(dir, name).apply { writeBytes(file.bytes) }
    }

    companion object {
        /** US-46.2: beside the video when its folder is a writable local folder, else [appDir]. */
        internal fun saveDir(scheme: String?, path: String?, appDir: File): File =
            path?.takeIf { scheme == "file" }?.let { File(it).parentFile }?.takeIf { it.canWrite() }
                ?: appDir.apply { mkdirs() }
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "video"
    }

    private fun hashOf(uri: Uri): String? {
        val pfd = when (uri.scheme) {
            "content" -> appContext.contentResolver.openFileDescriptor(uri, "r")
            else -> android.os.ParcelFileDescriptor.open(File(uri.path ?: return null), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        } ?: return null
        return pfd.use {
            FileInputStream(it.fileDescriptor).channel.use { ch ->
                OpenSubtitles.hash(ch.size()) { pos, buf -> ch.read(ByteBuffer.wrap(buf), pos) }
            }
        }
    }
}

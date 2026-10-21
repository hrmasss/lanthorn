package app.player.feature.library.smb

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import com.hierynomus.smbj.share.File
import kotlinx.coroutines.runBlocking

/**
 * Serves files on SMB shares as seekable file descriptors, so the player opens a share file the
 * way it opens any content URI (`fd://`), and mpv seeks in it as in a local file.
 * Uri: `content://<applicationId>.smb/<serverId>/<path with / separators>`.
 */
class SmbProvider : ContentProvider() {
    private val io by lazy { HandlerThread("smb-io").apply { start() } }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = requireNotNull(context)
        val (server, path) = resolve(context, uri)
        val file = SmbClient.openRead(server, path)
        val storage = context.getSystemService(StorageManager::class.java)
        return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, Reader(file), Handler(io.looper))
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val name = uri.lastPathSegment.orEmpty()
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
    }

    override fun getType(uri: Uri): String = "video/*"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    /** Serves mpv's small reads from [BlockReader], which keeps the blocks ahead fetching. */
    private class Reader(private val file: File) : ProxyFileDescriptorCallback() {
        private val blocks = BlockReader(file.fileInformation.standardInformation.endOfFile) { pos, buf, off, len ->
            file.read(buf, pos, off, len)
        }

        override fun onGetSize() = blocks.size

        override fun onRead(offset: Long, length: Int, data: ByteArray): Int = try {
            blocks.read(offset, length, data)
        } catch (e: Exception) {
            throw ErrnoException("smb read", OsConstants.EIO)
        }

        override fun onRelease() {
            blocks.close()
            runCatching { file.close() }
        }
    }

    companion object {
        fun authority(context: Context) = "${context.packageName}.smb"

        fun uri(context: Context, server: SmbServer, path: String): Uri =
            Uri.Builder().scheme("content").authority(authority(context))
                .appendPath(server.id)
                .apply { path.split('\\').forEach { appendPath(it) } }
                .build()

        private fun resolve(context: Context, uri: Uri): Pair<SmbServer, String> {
            val segments = uri.pathSegments
            val server = runBlocking { SmbServers(context).get(segments.first()) } ?: error("no such server")
            return server to segments.drop(1).joinToString("\\")
        }
    }
}

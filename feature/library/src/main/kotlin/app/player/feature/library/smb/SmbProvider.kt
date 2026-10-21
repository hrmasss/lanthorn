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

    /**
     * Reads ahead in 1 MiB blocks: mpv asks for small pieces, and each SMB round trip costs far
     * more than the bytes. Two blocks cover a forward read crossing a block edge.
     */
    private class Reader(private val file: File) : ProxyFileDescriptorCallback() {
        private val size = file.fileInformation.standardInformation.endOfFile
        private val blocks = LinkedHashMap<Long, ByteArray>(4, 0.75f, true)

        override fun onGetSize() = size

        override fun onRead(offset: Long, length: Int, data: ByteArray): Int {
            var done = 0
            try {
                while (done < length && offset + done < size) {
                    val pos = offset + done
                    val index = pos / BLOCK
                    val block = blocks.getOrPut(index) { fetch(index) }.also { trim() }
                    val inBlock = (pos - index * BLOCK).toInt()
                    val n = minOf(length - done, block.size - inBlock)
                    if (n <= 0) break
                    System.arraycopy(block, inBlock, data, done, n)
                    done += n
                }
            } catch (e: Exception) {
                throw ErrnoException("smb read", OsConstants.EIO)
            }
            return done
        }

        private fun fetch(index: Long): ByteArray {
            val start = index * BLOCK
            val want = minOf(BLOCK.toLong(), size - start).toInt()
            val buf = ByteArray(want)
            var got = 0
            while (got < want) {
                val n = file.read(buf, start + got, got, want - got)
                if (n <= 0) break
                got += n
            }
            return if (got == want) buf else buf.copyOf(got)
        }

        private fun trim() {
            while (blocks.size > 2) blocks.remove(blocks.keys.first())
        }

        override fun onRelease() {
            runCatching { file.close() }
        }

        companion object {
            const val BLOCK = 1 shl 20
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

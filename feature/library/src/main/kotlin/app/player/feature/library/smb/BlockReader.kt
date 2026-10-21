package app.player.feature.library.smb

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Serves reads of a remote file from 1 MiB blocks and keeps the next [ahead] blocks fetching in the
 * background (US-62.2). With a file descriptor mpv reads only about a second ahead, so without this
 * any network pause longer than that stalls playback; [ahead] = 8 holds about 6 s of a 10 Mbit/s file.
 *
 * [readAt] reads up to `len` bytes at `pos` into `buf` from `off` and returns the count. All calls to
 * it run on one background thread, in order, so the remote client is never used concurrently.
 */
class BlockReader(
    val size: Long,
    private val ahead: Int = AHEAD,
    private val block: Int = BLOCK,
    private val readAt: (pos: Long, buf: ByteArray, off: Int, len: Int) -> Int,
) {
    private val fetcher: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "smb-read-ahead") }
    private val blocks = HashMap<Long, Future<ByteArray>>()

    /** Fills [data] with up to [length] bytes at [offset]; fewer only at the end of the file. */
    fun read(offset: Long, length: Int, data: ByteArray): Int {
        var done = 0
        while (done < length && offset + done < size) {
            val pos = offset + done
            val index = pos / block
            val bytes = blockAt(index)
            val inBlock = (pos - index * block).toInt()
            val n = minOf(length - done, bytes.size - inBlock)
            if (n <= 0) break
            System.arraycopy(bytes, inBlock, data, done, n)
            done += n
        }
        return done
    }

    private fun blockAt(index: Long): ByteArray {
        val wanted = synchronized(blocks) {
            // Keep the block before (a read crossing back over an edge) up to the read-ahead window;
            // anything else is a seek's leftovers: drop it, and skip it if it has not started yet.
            blocks.keys.filter { it < index - 1 || it > index + ahead }.forEach { blocks.remove(it)?.cancel(false) }
            val wanted = submit(index)
            for (i in index + 1..minOf(index + ahead, (size - 1) / block)) submit(i)
            wanted
        }
        return try {
            wanted.get()
        } catch (e: Exception) {
            // A failed read is not kept: the next read of this block asks the server again.
            synchronized(blocks) { blocks.remove(index) }
            throw e
        }
    }

    private fun submit(index: Long): Future<ByteArray> =
        blocks.getOrPut(index) { fetcher.submit<ByteArray> { fetch(index) } }

    private fun fetch(index: Long): ByteArray {
        val start = index * block
        val want = minOf(block.toLong(), size - start).toInt()
        val buf = ByteArray(want)
        var got = 0
        while (got < want) {
            val n = readAt(start + got, buf, got, want - got)
            if (n <= 0) break
            got += n
        }
        return if (got == want) buf else buf.copyOf(got)
    }

    /** Blocks held or in flight, for tests. */
    internal fun held(): Set<Long> = synchronized(blocks) { blocks.keys.toSet() }

    fun close() {
        fetcher.shutdownNow()
    }

    companion object {
        const val BLOCK = 1 shl 20
        const val AHEAD = 8
    }
}

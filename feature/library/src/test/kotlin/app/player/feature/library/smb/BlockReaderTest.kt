package app.player.feature.library.smb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BlockReaderTest {
    private val file = ByteArray(10 * 16 + 5) { it.toByte() }

    private fun reader(ahead: Int = 3, reads: MutableList<Long> = Collections.synchronizedList(mutableListOf())) =
        BlockReader(file.size.toLong(), ahead = ahead, block = 16) { pos, buf, off, len ->
            reads += pos
            val n = minOf(len, (file.size - pos).toInt())
            System.arraycopy(file, pos.toInt(), buf, off, n)
            n
        }

    @Test fun `reads across block edges and stops at the end`() {
        val r = reader()
        val out = ByteArray(40)
        assertEquals(40, r.read(10, 40, out))
        assertArrayEquals(file.copyOfRange(10, 50), out)
        val tail = ByteArray(40)
        assertEquals(15, r.read(150, 40, tail))
        assertArrayEquals(file.copyOfRange(150, 165), tail.copyOf(15))
        r.close()
    }

    @Test fun `us_62_2 blocks ahead are fetched before they are asked for`() {
        val reads = Collections.synchronizedList(mutableListOf<Long>())
        val r = reader(ahead = 3, reads = reads)
        r.read(0, 4, ByteArray(4))
        val deadline = System.currentTimeMillis() + 2_000
        while (reads.size < 4 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(listOf(0L, 16L, 32L, 48L), reads.toList())
        r.read(16, 16, ByteArray(16))
        assertEquals("the next block came from read-ahead, not a second read", 1, reads.count { it == 16L })
        r.close()
    }

    @Test fun `a seek drops the old window`() {
        val r = reader(ahead = 2)
        r.read(0, 4, ByteArray(4))
        r.read(128, 4, ByteArray(4))
        assertEquals(setOf(8L, 9L, 10L), r.held())
        r.close()
    }

    @Test fun `a failed read is retried on the next read`() {
        var fail = true
        val latch = CountDownLatch(1)
        val r = BlockReader(32, ahead = 0, block = 16) { pos, buf, off, len ->
            if (fail) { fail = false; latch.countDown(); throw java.io.IOException("drop") }
            System.arraycopy(file, pos.toInt(), buf, off, len); len
        }
        val first = runCatching { r.read(0, 4, ByteArray(4)) }
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertTrue(first.isFailure)
        val out = ByteArray(4)
        assertEquals(4, r.read(0, 4, out))
        assertArrayEquals(file.copyOfRange(0, 4), out)
        r.close()
    }
}

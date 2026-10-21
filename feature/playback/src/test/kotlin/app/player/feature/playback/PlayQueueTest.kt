package app.player.feature.playback

import app.player.core.engine.EngineMessage
import app.player.core.testing.Ears
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayQueueTest {
    private val five = listOf("a", "b", "c", "d", "e")

    @Ears("US-24.1")
    @Test fun `loop off plays in list order and stops after the last (US-24_1)`() {
        val q = PlayQueue(five, 2)
        val seen = mutableListOf(q.current)
        while (q.next(LoopMode.Off)) seen += q.current
        assertEquals(listOf("c", "d", "e"), seen)
        assertFalse(q.hasNext(LoopMode.Off))
    }

    @Ears("US-24.3")
    @Test fun `loop all goes from the last back to the first (US-24_3)`() {
        val q = PlayQueue(five, 4)
        assertTrue(q.hasNext(LoopMode.All))
        assertTrue(q.next(LoopMode.All))
        assertEquals("a", q.current)
        assertTrue(q.previous(LoopMode.All))
        assertEquals("e", q.current)
    }

    @Ears("US-24.4")
    @Test fun `shuffle plays every item once before any repeats (US-24_4)`() {
        repeat(50) { seed ->
            val q = PlayQueue(five, 1, Random(seed))
            q.setShuffle(true)
            assertEquals("b", q.current)
            val round1 = mutableListOf(q.current)
            repeat(4) { assertTrue(q.next(LoopMode.All)); round1 += q.current }
            assertEquals(five.toSet(), round1.toSet())
            val round2 = mutableListOf<String>()
            repeat(5) { assertTrue(q.next(LoopMode.All)); round2 += q.current }
            assertEquals(five.toSet(), round2.toSet())
            assertTrue(round2.first() != round1.last())
        }
    }

    @Test fun `shuffle off goes back to list order from the current item`() {
        val q = PlayQueue(five, 0, Random(3))
        q.setShuffle(true)
        q.next(LoopMode.Off)
        val now = q.current
        q.setShuffle(false)
        assertEquals(now, q.current)
        if (q.next(LoopMode.Off)) assertEquals(five[five.indexOf(now) + 1], q.current)
    }

    @Test fun `shuffle ends after one round without loop all`() {
        val q = PlayQueue(five, 0, Random(1))
        q.setShuffle(true)
        var n = 1
        while (q.next(LoopMode.Off)) n++
        assertEquals(5, n)
    }

    @Ears("US-75.3")
    @Test fun `every item failed means the whole queue failed (US-75_3)`() {
        val q = PlayQueue(listOf("a", "b"), 0)
        q.markFailed()
        assertFalse(q.allFailed)
        q.next(LoopMode.All)
        q.markFailed()
        assertTrue(q.allFailed)
        q.markPlayed()
        assertFalse(q.allFailed)
    }

    @Ears("US-75.1")
    @Test fun `failure reasons are named from the error and mpv's lines (US-75_1)`() {
        fun line(t: String) = EngineMessage("x", EngineMessage.Level.Error, t)
        assertEquals(FailureReason.MissingFile, Failures.classify("/sdcard/a.mp4: open failed: ENOENT (No such file or directory)", emptyList()))
        assertEquals(FailureReason.NoPermission, Failures.classify("Permission Denial: reading com.example.Provider", emptyList()))
        assertEquals(FailureReason.UnsupportedCodec, Failures.classify(null, listOf(line("Failed to initialize a decoder for codec 'prores'."))))
        assertEquals(FailureReason.Damaged, Failures.classify("cannot play this file", listOf(line("Failed to recognize file format."))))
    }

    @Ears("US-29.1")
    @Test fun `smart previous restarts after 5 s, otherwise goes back (US-29_1)`() {
        assertTrue(previousRestarts(smartPrevious = true, positionMs = 5_001))
        assertFalse(previousRestarts(smartPrevious = true, positionMs = 5_000))
        assertFalse(previousRestarts(smartPrevious = false, positionMs = 60_000))
    }
}

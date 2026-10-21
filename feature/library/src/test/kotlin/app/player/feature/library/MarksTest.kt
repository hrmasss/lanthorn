package app.player.feature.library

import app.player.core.data.PlaybackPosition
import app.player.core.data.library.LibraryVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_800_000_000L
private const val DAY = 86_400L

private fun v(id: Long, folder: String = "external_primary/A/", modified: Long = NOW - 30 * DAY, available: Boolean = true) =
    LibraryVideo(id, "content://media/external_primary/video/media/$id", "external_primary", folder, null, "v$id.mp4",
        1, 100_000, 1920, 1080, modified, "video/mp4", available)

private fun pos(video: LibraryVideo, at: Long, duration: Long = 100_000, updated: Long = 0) =
    video.uri to PlaybackPosition(video.uri, at, duration, updated)

class MarksTest {
    @Test fun `us_07_1 new means modified within the period and never played`() {
        val fresh = v(1, modified = NOW - 2 * DAY)
        val old = v(2, modified = NOW - 10 * DAY)
        val playedFresh = v(3, modified = NOW - 1 * DAY)
        val m = marks(listOf(fresh, old, playedFresh), mapOf(pos(playedFresh, 5_000)), NOW, 7, true)
        assertTrue(m.getValue(1).isNew)
        assertFalse(m.getValue(2).isNew)
        assertFalse(m.getValue(3).isNew)
        assertTrue(marks(listOf(old), emptyMap(), NOW, 14, true).getValue(2).isNew)
    }

    @Test fun `us_07_2 progress is the played share`() {
        val a = v(1)
        assertEquals(0.4f, marks(listOf(a), mapOf(pos(a, 40_000)), NOW, 7, true).getValue(1).progress!!, 0.001f)
        assertNull("under a second is no progress", marks(listOf(a), mapOf(pos(a, 500)), NOW, 7, true).getValue(1).progress)
    }

    @Test fun `us_07_3 the last 5 percent or the end is finished, with no bar`() {
        val a = v(1)
        val atEnd = marks(listOf(a), mapOf(pos(a, 100_000)), NOW, 7, true).getValue(1)
        assertTrue(atEnd.finished)
        assertNull(atEnd.progress)
        assertTrue(marks(listOf(a), mapOf(pos(a, 95_000)), NOW, 7, true).getValue(1).finished)
        assertFalse(marks(listOf(a), mapOf(pos(a, 94_000)), NOW, 7, true).getValue(1).finished)
    }

    @Test fun `us_07_4 last played per folder, and only with the switch on`() {
        val a1 = v(1, "external_primary/A/"); val a2 = v(2, "external_primary/A/"); val b1 = v(3, "external_primary/B/")
        val ps = mapOf(pos(a1, 5_000, updated = 10), pos(a2, 5_000, updated = 20), pos(b1, 5_000, updated = 5))
        val m = marks(listOf(a1, a2, b1), ps, NOW, 7, true)
        assertEquals(listOf(2L, 3L), m.filterValues { it.lastPlayed }.keys.sorted())
        assertTrue(marks(listOf(a1, a2, b1), ps, NOW, 7, false).values.none { it.lastPlayed })
    }

    @Test fun `us_08 resume button video is the newest resume point still listed`() {
        val a = v(1); val gone = v(2, available = false); val b = v(3)
        val ps = mapOf(pos(a, 5_000, updated = 10), pos(gone, 5_000, updated = 30), pos(b, 5_000, updated = 20),
            "content://media/external_primary/video/media/99" to PlaybackPosition("content://media/external_primary/video/media/99", 1, 2, 40))
        assertEquals(3L, lastPlayed(listOf(a, gone, b), ps)?.id)
        assertNull("US-08.3: nothing left", lastPlayed(listOf(gone), ps))
    }
}

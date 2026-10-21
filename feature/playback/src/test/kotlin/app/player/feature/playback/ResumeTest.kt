package app.player.feature.playback

import app.player.core.data.PlaybackPosition
import app.player.core.engine.PlaybackState
import app.player.core.testing.Ears
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumeTest {
    private fun playing(positionMs: Long, durationMs: Long = 600_000, ended: Boolean = false) =
        PlaybackState(uri = "content://media/external_primary/video/media/7", loaded = true, paused = false,
            positionMs = positionMs, durationMs = durationMs, ended = ended)

    private fun saved(positionMs: Long, durationMs: Long = 600_000) = PlaybackPosition("u", positionMs, durationMs, 0)

    @Test fun `position is saved rounded down to the second (US-22_1)`() {
        val row = Resume.toSave(playing(123_987), now = 42)!!
        assertEquals(123_000, row.positionMs)
        assertEquals(600_000, row.durationMs)
        assertEquals("content://media/external_primary/video/media/7", row.uri)
        assertEquals(42, row.updatedAt)
    }

    @Test fun `the last 5 percent saves as finished, position equals duration (US-22_1, US-07_3)`() {
        assertEquals(600_000, Resume.toSave(playing(570_000), 0)!!.positionMs)
        assertEquals(569_000, Resume.toSave(playing(569_999), 0)!!.positionMs)
        assertEquals(600_000, Resume.toSave(playing(596_000, ended = true), 0)!!.positionMs)
    }

    @Ears("US-22.1", "US-07.3")
    @Test fun `an end reported mid-file, as mpv does while shutting down, keeps the real position (US-22_1)`() {
        assertEquals(14_000, Resume.toSave(playing(14_000, durationMs = 60_000, ended = true), 0)!!.positionMs)
    }

    @Test fun `nothing is saved before load, on error or without a duration`() {
        assertNull(Resume.toSave(playing(5_000).copy(loaded = false), 0))
        assertNull(Resume.toSave(playing(5_000).copy(error = "cannot play this file"), 0))
        assertNull(Resume.toSave(playing(5_000, durationMs = 0), 0))
        assertNull(Resume.toSave(playing(5_000).copy(uri = null), 0))
    }

    @Test fun `resume yes starts at the saved position (US-22_2)`() {
        assertEquals(StartAt.Resume(123_000), Resume.startAt(saved(123_000), ResumeMode.Yes, laterInQueue = false, firstOnly = false))
    }

    @Test fun `resume ask asks, resume no starts at 0 (US-22_3)`() {
        assertEquals(StartAt.Ask(123_000), Resume.startAt(saved(123_000), ResumeMode.Ask, false, false))
        assertEquals(StartAt.Beginning, Resume.startAt(saved(123_000), ResumeMode.No, false, false))
    }

    @Test fun `resume only the first file starts later queue files at 0 (US-22_4)`() {
        assertEquals(StartAt.Beginning, Resume.startAt(saved(123_000), ResumeMode.Yes, laterInQueue = true, firstOnly = true))
        assertEquals(StartAt.Resume(123_000), Resume.startAt(saved(123_000), ResumeMode.Yes, laterInQueue = false, firstOnly = true))
        assertEquals(StartAt.Resume(123_000), Resume.startAt(saved(123_000), ResumeMode.Yes, laterInQueue = true, firstOnly = false))
    }

    @Test fun `a finished file, a missing row or under a second starts at 0`() {
        assertEquals(StartAt.Beginning, Resume.startAt(saved(600_000), ResumeMode.Yes, false, false))
        assertEquals(StartAt.Beginning, Resume.startAt(saved(570_000), ResumeMode.Ask, false, false))
        assertEquals(StartAt.Beginning, Resume.startAt(null, ResumeMode.Yes, false, false))
        assertEquals(StartAt.Beginning, Resume.startAt(saved(900), ResumeMode.Yes, false, false))
    }

    @Test fun `a playing file is saved at least every 10 s (US-22_5)`() {
        assert(Resume.SAVE_EVERY_MS <= 10_000)
    }

    @Test fun `time reads as m_ss or h_mm_ss`() {
        assertEquals("2:03", formatTime(123_456))
        assertEquals("1:02:03", formatTime(3_723_000))
        assertEquals("0:00", formatTime(0))
    }
}

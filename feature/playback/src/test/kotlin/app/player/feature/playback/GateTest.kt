package app.player.feature.playback

import app.player.core.data.PlaybackPosition
import app.player.core.engine.EngineMessage
import app.player.core.engine.PlaybackState
import app.player.core.testing.Ears
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GateTest {
    private fun playing(uri: String) = PlaybackState(uri = uri, loaded = true, paused = false, positionMs = 12_000, durationMs = 60_000)

    @Ears("US-22.1")
    @Test fun `live and unseekable streams get no resume point (US-22_1)`() {
        assertNull(Resume.toSave(playing("rtmp://10.0.2.2:1945/live"), 0))
        assertNull(Resume.toSave(playing("rtsp://cam.local/stream"), 0))
        assertNull(Resume.toSave(playing("https://example.com/live.m3u8"), 0, seekable = false))
        assertNull(Resume.toSave(playing("https://example.com/live.m3u8"), 0, seekable = true, live = true))
        assertNotNull(Resume.toSave(playing("https://example.com/film.mp4"), 0, seekable = true))
        assertNotNull(Resume.toSave(playing("content://media/external_primary/video/media/7"), 0, seekable = null))
    }

    @Ears("US-22.2")
    @Test fun `a live stream never resumes, even from an old row (US-22_2)`() {
        val old = PlaybackPosition("rtmp://10.0.2.2:1945/live", 12_000, 60_000, 0)
        assertEquals(StartAt.Beginning, Resume.startAt(old, ResumeMode.Yes, laterInQueue = false, firstOnly = false))
    }

    @Ears("US-75.1")
    @Test fun `file names come from the path, never a bare id (US-75_1)`() {
        assertEquals("Long.mp4", Failures.nameFromPath("primary:Movies/PB/Single/Long.mp4"))
        assertEquals("clip.mkv", Failures.nameFromPath("/sdcard/Movies/clip.mkv"))
        assertNull(Failures.nameFromPath("999999"))
        assertNull(Failures.nameFromPath(""))
    }

    @Ears("US-75.1")
    @Test fun `info lines are sentences, the codec is named (US-75_1)`() {
        val lines = listOf(
            EngineMessage("vd", EngineMessage.Level.Error, "Failed to initialize a decoder for codec 'zq9x'."),
            EngineMessage("cplayer", EngineMessage.Level.Warn, "warning only"),
            EngineMessage("vd", EngineMessage.Level.Error, "Failed to initialize a decoder for codec 'zq9x'."),
        )
        assertEquals(listOf("Cannot play this file.", "Failed to initialize a decoder for codec 'zq9x'."), Failures.technical("cannot play this file", lines))
        assertEquals("zq9x", Failures.codecOf(lines))
    }

    @Ears("US-75.1")
    @Test fun `a file that ends early after an mpv error failed, a normal end did not (US-75_1)`() {
        val error = listOf(EngineMessage("ffmpeg/demuxer", EngineMessage.Level.Error, "error reading packet"))
        assert(Failures.endedEarly(positionMs = 0, durationMs = 4_000, lines = error))
        assert(!Failures.endedEarly(positionMs = 3_990, durationMs = 4_000, lines = error))
        assert(!Failures.endedEarly(positionMs = 0, durationMs = 4_000, lines = emptyList()))
        assert(!Failures.endedEarly(positionMs = 200, durationMs = 900, lines = error))
    }
}

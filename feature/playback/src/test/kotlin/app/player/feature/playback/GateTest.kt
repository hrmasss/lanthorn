package app.player.feature.playback

import app.player.core.data.PlaybackPosition
import app.player.core.engine.EngineMessage
import app.player.core.engine.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GateTest {
    private fun playing(uri: String) = PlaybackState(uri = uri, loaded = true, paused = false, positionMs = 12_000, durationMs = 60_000)

    @Test fun `live and unseekable streams get no resume point (US-22_1)`() {
        assertNull(Resume.toSave(playing("rtmp://10.0.2.2:1945/live"), 0))
        assertNull(Resume.toSave(playing("rtsp://cam.local/stream"), 0))
        assertNull(Resume.toSave(playing("https://example.com/live.m3u8"), 0, seekable = false))
        assertNull(Resume.toSave(playing("https://example.com/live.m3u8"), 0, seekable = true, live = true))
        assertNotNull(Resume.toSave(playing("https://example.com/film.mp4"), 0, seekable = true))
        assertNotNull(Resume.toSave(playing("content://media/external_primary/video/media/7"), 0, seekable = null))
    }

    @Test fun `a live stream never resumes, even from an old row (US-22_2)`() {
        val old = PlaybackPosition("rtmp://10.0.2.2:1945/live", 12_000, 60_000, 0)
        assertEquals(StartAt.Beginning, Resume.startAt(old, ResumeMode.Yes, laterInQueue = false, firstOnly = false))
    }

    @Test fun `file names come from the path, never a bare id (US-75_1)`() {
        assertEquals("Long.mp4", Failures.nameFromPath("primary:Movies/PB/Single/Long.mp4"))
        assertEquals("clip.mkv", Failures.nameFromPath("/sdcard/Movies/clip.mkv"))
        assertNull(Failures.nameFromPath("999999"))
        assertNull(Failures.nameFromPath(""))
    }

    @Test fun `info lines are sentences, the codec is named (US-75_1)`() {
        val lines = listOf(
            EngineMessage("vd", EngineMessage.Level.Error, "Failed to initialize a decoder for codec 'zq9x'."),
            EngineMessage("cplayer", EngineMessage.Level.Warn, "warning only"),
            EngineMessage("vd", EngineMessage.Level.Error, "Failed to initialize a decoder for codec 'zq9x'."),
        )
        assertEquals(listOf("Cannot play this file.", "Failed to initialize a decoder for codec 'zq9x'."), Failures.technical("cannot play this file", lines))
        assertEquals("zq9x", Failures.codecOf(lines))
    }
}

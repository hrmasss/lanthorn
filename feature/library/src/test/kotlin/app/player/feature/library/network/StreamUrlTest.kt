package app.player.feature.library.network

import app.player.core.testing.Ears
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUrlTest {
    @Ears("US-61.1")
    @Test fun `us_61_1 every stream scheme is accepted`() {
        listOf(
            "http://10.0.2.2:8000/clip.mp4",
            "https://example.com/live/index.m3u8",
            "https://example.com/dash/manifest.mpd",
            "rtsp://cam.local:554/stream1",
            "rtmp://live.example.com/app/key",
            "rtsps://cam.local/stream",
            "rtmps://live.example.com/app/key",
        ).forEach { assertEquals(it, parseStreamUrl(it)) }
    }

    @Test fun `scheme is lower-cased and a bare host gets https`() {
        assertEquals("http://Example.com/a.mp4", parseStreamUrl("  HTTP://Example.com/a.mp4 "))
        assertEquals("https://example.com/live.m3u8", parseStreamUrl("example.com/live.m3u8"))
    }

    @Test fun `not a stream`() {
        listOf("", "   ", "ftp://host/a.mp4", "file:///sdcard/a.mp4", "content://media/1", "http://", "a b", "javascript:alert(1)")
            .forEach { assertNull(it, parseStreamUrl(it)) }
    }

    @Test fun `network or local`() {
        assertTrue(isNetworkUrl("https://a/b"))
        assertTrue(isNetworkUrl("RTSP://a/b"))
        assertFalse(isNetworkUrl("content://media/external/video/media/1"))
        assertFalse(isNetworkUrl("/sdcard/a.mp4"))
    }

    @Ears("US-61.4")
    @Test fun `us_61_4 failure reasons from mpv lines`() {
        assertEquals(StreamFailure.NotFound, classifyFailure(listOf("[ffmpeg] http: HTTP error 404 Not Found", "[file] Failed to recognize file format.")))
        assertEquals(StreamFailure.Timeout, classifyFailure(listOf("[ffmpeg] tcp: Connection timed out")))
        assertEquals(StreamFailure.Timeout, classifyFailure(listOf("[ffmpeg] tcp: Failed to resolve hostname nowhere.invalid")))
        assertEquals(StreamFailure.Timeout, classifyFailure(listOf("[ffmpeg] tcp: Connection refused")))
        assertEquals(StreamFailure.Unsupported, classifyFailure(listOf("[file] Failed to recognize file format.")))
        assertEquals(StreamFailure.Other, classifyFailure(listOf("[cplayer] something else")))
    }
}

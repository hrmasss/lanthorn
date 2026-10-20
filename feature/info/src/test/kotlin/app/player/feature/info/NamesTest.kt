package app.player.feature.info

import org.junit.Assert.assertEquals
import org.junit.Test

class NamesTest {
    @Test fun containerFromExtensionThenFormat() {
        assertEquals("MP4", Names.container("mov,mp4,m4a,3gp,3g2,mj2", "clip.mp4"))
        assertEquals("MOV", Names.container("mov,mp4,m4a,3gp,3g2,mj2", "clip.MOV"))
        assertEquals("MKV", Names.container("matroska,webm", "a.mkv"))
        assertEquals("WebM", Names.container("matroska,webm", "a.webm"))
        assertEquals("MP4", Names.container("mov,mp4,m4a,3gp,3g2,mj2", "stream"))
        assertEquals("MPEG-TS", Names.container("mpegts", null))
        assertEquals("-", Names.container(null, null))
    }

    @Test fun codecsByTheirCommonNames() {
        assertEquals("H.264", Names.codec("h264"))
        assertEquals("HEVC (H.265)", Names.codec("hevc"))
        assertEquals("AAC", Names.codec("aac"))
        assertEquals("SRT", Names.codec("subrip"))
        assertEquals("MP4 text", Names.codec("mov_text"))
        assertEquals("PRORES", Names.codec("prores"))
    }

    @Test fun channelLayouts() {
        assertEquals("Mono", Names.channels(1))
        assertEquals("Stereo", Names.channels(2))
        assertEquals("5.1", Names.channels(6))
        assertEquals("4 channels", Names.channels(4))
        assertEquals("-", Names.channels(null))
    }
}

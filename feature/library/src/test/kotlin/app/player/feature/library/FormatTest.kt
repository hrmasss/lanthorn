package app.player.feature.library

import app.player.core.testing.Ears
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {
    @Ears("US-02.1")
    @Test fun `us_02_1 length reads as minutes or hours`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("4:05", formatDuration(245_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
        assertEquals("0:01", formatDuration(600))
    }

    @Ears("US-02.2")
    @Test fun `us_02_2 size in decimal units`() {
        assertEquals("999 B", formatSize(999, Locale.US))
        assertEquals("1.5 kB", formatSize(1_500, Locale.US))
        assertEquals("850 MB", formatSize(850_000_000, Locale.US))
        assertEquals("1.4 GB", formatSize(1_400_000_000, Locale.US))
    }

    @Ears("US-02.2")
    @Test fun `us_02_2 resolution, frame rate and extension`() {
        assertEquals("1920×1080", formatResolution(1920, 1080))
        assertNull(formatResolution(0, 0))
        assertEquals("25 fps", formatFps(25f, Locale.US))
        assertEquals("29.97 fps", formatFps(29.97f, Locale.US))
        assertEquals("23.5 fps", formatFps(23.5f, Locale.US))
        assertNull(formatFps(0f))
        assertEquals("MKV", extensionOf("Film.mkv"))
        assertNull(extensionOf("README"))
    }

    @Test fun `names drop their extension only`() {
        assertEquals("Episode 2", displayName("Episode 2.mp4"))
        assertEquals("v1.2 final", displayName("v1.2 final.webm"))
        assertEquals(".hidden", displayName(".hidden"))
        assertEquals("no extension", displayName("no extension"))
    }
}

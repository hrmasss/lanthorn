package app.player.feature.audio

import app.player.core.testing.Ears
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioDelayTest {
    private val mpv = FakeMpv()
    private val delay = AudioDelay(mpv)

    @Ears("US-48.2")
    @Test fun `bluetooth delay adds only while on bluetooth (US-48_2)`() {
        delay.setBase(100)
        delay.setBluetooth(200, active = true)
        assertEquals(300, delay.effectiveMs)
        assertEquals("set audio-delay=0.3", mpv.calls.last())
        delay.setBluetooth(200, active = false)
        assertEquals("set audio-delay=0.1", mpv.calls.last())
        assertEquals(100, delay.baseMs.value)
    }

    @Ears("US-48.1")
    @Test fun `base snaps and clamps like the player delay (US-48_1)`() {
        delay.setBase(12_345)
        assertEquals(10_000, delay.baseMs.value)
        delay.setBase(-14)
        assertEquals(-10, delay.baseMs.value)
    }

    @Ears("US-47.2")
    @Test fun `alang carries every iso form of the language (US-47_2)`() {
        assertEquals("fr,fra,fre", alangFor("fr"))
        assertEquals("en,eng", alangFor("en"))
        assertEquals("zh,zho,chi", alangFor("zh"))
        assertEquals("", alangFor(""))
    }
}

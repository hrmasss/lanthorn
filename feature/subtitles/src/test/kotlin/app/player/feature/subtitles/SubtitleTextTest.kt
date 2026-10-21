package app.player.feature.subtitles

import app.player.core.testing.Ears
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class SubtitleTextTest {
    private val line = "1\n00:00:01,000 --> 00:00:02,000\n"

    @Ears("US-44.1")

    @Test fun `utf-8 is kept`() {
        val text = line + "Žluťoučký kůň — 你好"
        assertEquals(text, SubtitleText.decode(text.toByteArray(Charsets.UTF_8))!!.text)
    }

    @Ears("US-44.1")

    @Test fun `bom wins, also over a forced charset`() {
        val text = line + "مرحبا"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val d = SubtitleText.decode(bytes, forced = Charset.forName("windows-1252"))!!
        assertEquals(text, d.text)
        assertEquals(Charsets.UTF_16LE, d.charset)
    }

    @Ears("US-44.2")
    @Test fun `forced charset decodes a legacy file (US-44_2)`() {
        val text = line + "Příliš žluťoučký kůň"
        val cp1250 = Charset.forName("windows-1250")
        assertEquals(text, SubtitleText.decode(text.toByteArray(cp1250), forced = cp1250)!!.text)
    }

    @Ears("US-44.1")
    @Test fun `legacy bytes without a pick are left to mpv's detection (US-44_1)`() {
        val text = line + "Привет"
        assertEquals(null, SubtitleText.decode(text.toByteArray(Charset.forName("windows-1251"))))
    }

    @Test fun `binary subtitles are not decoded`() {
        assertTrue(SubtitleText.isBinary(byteArrayOf(0, 0, 1, 0xBA.toByte())))
        assertTrue(SubtitleText.isBinary("PG".toByteArray()))
        assertFalse(SubtitleText.isText("sub", byteArrayOf(0, 0, 1, 0xBA.toByte())))
        assertTrue(SubtitleText.isText("sub", "{1}{25}Hello".toByteArray()))
        assertFalse(SubtitleText.isText("sup", "anything".toByteArray()))
    }

    @Test fun `every encoding choice exists on the jvm`() {
        SubtitleText.CHOICES.forEach { Charset.forName(it) }
    }
}

class SubtitleFontsTest {
    @Test fun `fonts conf names the dirs, a cache and the generic families`() {
        val conf = SubtitleFonts.config(listOf("/system/fonts"), "/c/fc")
        assertTrue(conf.contains("<dir>/system/fonts</dir>"))
        assertTrue(conf.contains("<cachedir>/c/fc</cachedir>"))
        assertTrue(conf.contains("<family>sans-serif</family><prefer><family>Roboto</family>"))
    }
}

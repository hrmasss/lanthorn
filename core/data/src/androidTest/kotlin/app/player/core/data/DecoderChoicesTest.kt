package app.player.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.data.choice.DecoderChoices
import app.player.core.data.choice.PerFileChoice
import app.player.core.data.choice.PerFileChoices
import app.player.core.data.choice.RememberPerFile
import app.player.core.engine.DecoderMode
import app.player.core.engine.DecoderSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** P1.1 per-file decoder choice and the shared per_file_choice table, on a device. */
@RunWith(AndroidJUnit4::class)
class DecoderChoicesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val choices = DecoderChoices(context)
    private val perFile = PerFileChoices(context)
    private val settings = DecoderSettings(context)
    private val remember = RememberPerFile(context)
    private val movie = "file:///sdcard/Movies/a.mkv"

    @Before fun clean(): Unit = runBlocking {
        perFile.clear()
        settings.reset()
        remember.set(true)
    }

    @After fun restore(): Unit = runBlocking { clean() }

    @Test fun pickOrder_US36_1(): Unit = runBlocking {
        assertEquals(DecoderMode.AUTO, choices.choose(movie))
        settings.setPreferSoftware(true)
        assertEquals(DecoderMode.SW, choices.choose(movie))
        settings.setForExtension("mkv", DecoderMode.HW_PLUS)
        assertEquals(DecoderMode.HW_PLUS, choices.choose(movie))
        assertEquals(DecoderMode.SW, choices.choose("file:///sdcard/Movies/b.mp4"))
        choices.rememberManualChoice(movie, DecoderMode.HW)
        assertEquals(DecoderMode.HW, choices.choose(movie))
    }

    @Test fun rememberedOnlyWhileTheSwitchIsOn_US36_3(): Unit = runBlocking {
        choices.rememberManualChoice(movie, DecoderMode.SW)
        assertEquals(DecoderMode.SW, choices.choose(movie))
        remember.set(false)
        assertEquals(DecoderMode.AUTO, choices.choose(movie))
        choices.rememberManualChoice("file:///x.mp4", DecoderMode.SW)
        remember.set(true)
        assertNull(perFile.get("file:///x.mp4", DecoderChoices.KEY))
        assertEquals(DecoderMode.SW, choices.choose(movie))
    }

    @Test fun capKeepsTheNewest2000Files(): Unit = runBlocking {
        val now = System.currentTimeMillis()
        perFile.restore((0 until PerFileChoices.CAP + 5).map { PerFileChoice("file:///v$it.mp4", "decoder", "SW", now + it) })
        val uris = perFile.all().map { it.uri }.toSet()
        assertEquals(PerFileChoices.CAP, uris.size)
        assertNull(perFile.get("file:///v0.mp4", "decoder"))
        assertEquals("SW", perFile.get("file:///v${PerFileChoices.CAP + 4}.mp4", "decoder"))
    }
}

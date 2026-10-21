package app.player.feature.subtitles

import app.player.core.testing.evidenceShot
import app.player.core.testing.Ears
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PropertyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File

/**
 * P1.26 US-44.3: Arabic, Hebrew, Devanagari and Thai lines drawn on the device, shot for comparison
 * with host libass references (tools/compare-glyphs.py). Same ASS, same embedded fonts, same size.
 */
class ComplexScriptsOnDeviceTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(200)

    private lateinit var scenario: ActivityScenario<VideoTestActivity>
    private lateinit var engine: PlayerEngine
    private val holder = Any()

    @Before fun start() = runBlocking {
        SubtitlePrefsStore(OnDevice.context).setShowAutomatically(false)
        scenario = ActivityScenario.launch(VideoTestActivity::class.java)
        engine = OnDevice.acquire(holder) { SubtitlesExtension.onEngineCreated(OnDevice.context, it) }
        scenario.onActivity { it.surface.engine = engine }
        Unit
    }

    @After fun close() = runBlocking {
        SubtitlePrefsStore(OnDevice.context).setShowAutomatically(true)
        runCatching { scenario.onActivity { it.surface.engine = null } }
        OnDevice.release(holder)
        scenario.close()
    }

    @Ears("US-44.3")
    @Test fun complexScriptsDrawnForComparison_US44_3() = runBlocking {
        engine.open(Uri.fromFile(OnDevice.asset("complex.mkv")))
        engine.await("track-list/count", PropertyType.Int64, 10_000) { (it ?: 0) >= 2 }
        engine.awaitAutoLoad()
        engine.set("sid", "1")
        engine.pause()
        listOf("arabic", "hebrew", "devanagari", "thai").forEachIndexed { i, script ->
            engine.seekTo(i * 3000L + 1500)
            engine.await("time-pos", PropertyType.Real, 5_000) { it != null && kotlin.math.abs(it - (i * 3 + 1.5)) < 0.2 }
            delay(1500)
            val text = engine.get("sub-text", PropertyType.Text)
            shot("us44-complex-$script")
            evidence("US-44.3 $script on screen: '$text' (shot us44-complex-$script.png)")
        }
    }

    private fun shot(name: String) {
        evidenceShot(name)
    }
}

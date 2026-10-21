package app.player.feature.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.data.choice.PerFileChoices
import app.player.core.data.choice.RememberPerFile
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PlayerSessions
import app.player.core.engine.PropertyType
import app.player.core.testing.Ears
import app.player.core.testing.evidenceShot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * P1.16 menus on a real engine (emulator). Waits go through the compose rule, so its frame clock
 * keeps running and the screen recomposes; a sleep loop would freeze it.
 */
class VideoMenuTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<PlayerActivity>
    private lateinit var engine: PlayerEngine
    private val clip by lazy { copy("clip.mp4") }

    private fun open() {
        scenario = ActivityScenario.launch(PlayerActivity.intent(context, clip))
        compose.waitUntil(15_000) { (PlayerSessions.current.value?.state?.value?.positionMs ?: 0) > 300 }
        engine = PlayerSessions.current.value!!
    }

    @After fun close() {
        scenario.close()
        // Leave no stored fit behind: other tests open the same clip and measure the picture.
        runBlocking {
            RememberPerFile(context).set(true)
            PerFileChoices(context).forget(clip.toString())
        }
    }

    /** Taps until the controls show: a slow emulator can drop the first tap while the player starts. */
    private fun menu() {
        repeat(3) {
            if (!shown("more")) {
                compose.onNodeWithTag("video").performClick()
                runCatching { compose.waitUntil(3_000) { shown("more") } }
            }
        }
        compose.onNodeWithTag("more").performClick()
        compose.waitUntil(3_000) { shown("shortcuts") }
    }

    private fun <T : Any> prop(name: String, type: PropertyType<T>): T? = runBlocking { engine.get(name, type) }

    @Ears("US-32.1")
    @Test fun shortcutsInMenu_US32_1() {
        open()
        menu()
        shot("p116-menu-shortcuts")
        compose.onNodeWithTag("sc-Mute").performClick()
        compose.waitUntil(3_000) { prop("mute", PropertyType.Flag) == true }
        // Loop, shuffle and background play are playback's hooks, not present in this test.
        compose.onNodeWithTag("sc-Mirror").performClick()
        compose.waitUntil(3_000) { prop("glsl-shaders", PropertyType.Text)?.contains("lanthorn-mirror") == true }
        compose.waitForIdle()
        shot("p116-shortcuts-on")
    }

    @Ears("US-57.1", "US-57.2")
    @Test fun pictureMenu_US57() {
        open()
        menu()
        compose.onNodeWithTag("menu-picture").performScrollTo().performClick()
        compose.waitUntil(3_000) { shown("picture-panel") }
        compose.onNodeWithTag("pic-night").performClick()
        compose.onNodeWithTag("pic-mirror").performClick()
        compose.waitUntil(3_000) {
            val shaders = prop("glsl-shaders", PropertyType.Text)
            shaders != null && shaders.contains("lanthorn-night") && shaders.contains("lanthorn-mirror")
        }
        log("picture menu: glsl-shaders ${prop("glsl-shaders", PropertyType.Text)}")
        shot("p116-picture-panel")
    }

    @Ears("US-53", "US-54")
    @Test fun fitAspectMenu_US53_US54() {
        open()
        menu()
        compose.onNodeWithTag("menu-fit-aspect").performScrollTo().performClick()
        compose.waitUntil(3_000) { shown("fit-panel") }
        compose.onNodeWithTag("aspect-4:3").performScrollTo().performClick()
        compose.waitUntil(3_000) { (prop("video-out-params/aspect", PropertyType.Real) ?: 0.0).let { kotlin.math.abs(it - 4.0 / 3) < 0.01 } }
        val aspect = prop("video-out-params/aspect", PropertyType.Real)!!
        log("aspect menu 4:3 -> video-out-params/aspect $aspect")
        assertEquals(4.0 / 3, aspect, 0.01)
        shot("p116-fit-panel")
    }

    /** Picks crop in the fit panel, leaves, resets mpv's panscan, opens the same file again. */
    private fun pickCropAndReopen(): Double {
        runBlocking { PerFileChoices(context).forget(clip.toString()) }
        open()
        menu()
        compose.onNodeWithTag("menu-fit-aspect").performScrollTo().performClick()
        compose.waitUntil(3_000) { shown("fit-panel") }
        compose.onNodeWithTag("fit-Crop").performScrollTo().performClick()
        compose.waitUntil(3_000) { prop("panscan", PropertyType.Real) == 1.0 }
        // The choice is written off the main thread; give it a moment before leaving.
        compose.waitUntil(3_000) { runBlocking { RememberPerFile(context).isEnabled() } == false || runBlocking { PerFileChoices(context).get(clip.toString(), FIT_CHOICE) } == "Crop" }
        scenario.close()
        runCatching { PlayerSessions.current.value?.set("panscan", 0.0) }
        open()
        // Remembered: crop comes back with the load. Not remembered: it never does.
        runCatching { compose.waitUntil(3_000) { prop("panscan", PropertyType.Real) == 1.0 } }
        return prop("panscan", PropertyType.Real) ?: -1.0
    }

    @Ears("US-53.2")
    @Test fun fitRememberedPerFile_US53_2() {
        runBlocking { RememberPerFile(context).set(true) }
        val panscan = pickCropAndReopen()
        log("remember choices on: crop picked, reopened -> panscan $panscan")
        assertEquals(1.0, panscan, 0.0)
    }

    @Ears("US-53.2")
    @Test fun fitNotRememberedWhenOff_US53_2() {
        runBlocking { RememberPerFile(context).set(false) }
        val panscan = pickCropAndReopen()
        log("remember choices off: crop picked, reopened -> panscan $panscan")
        assertEquals(0.0, panscan, 0.0)
    }

    @Ears("US-65.3")
    @Test fun settingsInsidePlayer_US65_3() {
        open()
        menu()
        compose.onNodeWithTag("menu-player-settings").performScrollTo().performClick()
        compose.waitUntil(3_000) { shown("player-settings") }
        compose.onNodeWithTag("set-back-twice").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue("still playing", !engine.state.value.paused)
        shot("p116-settings-in-player")
    }

    private fun shown(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun copy(asset: String): Uri {
        val file = File(context.cacheDir, asset)
        if (!file.exists()) {
            InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        return Uri.fromFile(file)
    }

    /** Saved per test by core/testing, so the EARS report links it to the criterion. */
    private fun shot(name: String) {
        SystemClock.sleep(300)
        evidenceShot(name)
    }

    private fun log(line: String) {
        android.util.Log.i("VideoMenuTest", line)
        val dir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "numbers.txt").appendText(line + "\n")
    }
}

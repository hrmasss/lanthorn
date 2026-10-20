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
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PlayerSessions
import app.player.core.engine.PropertyType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** P1.16 menus on a real engine (emulator). */
class VideoMenuTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<PlayerActivity>
    private lateinit var engine: PlayerEngine

    private fun open() {
        scenario = ActivityScenario.launch(PlayerActivity.intent(context, copy("clip.mp4")))
        waitUntil(10_000) { (PlayerSessions.current.value?.state?.value?.positionMs ?: 0) > 300 }
        engine = PlayerSessions.current.value!!
    }

    @After fun close() = scenario.close()

    private fun menu() {
        compose.onNodeWithTag("video").performClick()
        compose.waitUntil(2_000) { shown("more") }
        compose.onNodeWithTag("more").performClick()
        compose.waitUntil(2_000) { shown("shortcuts") }
    }

    @Test fun shortcutsInMenu_US32_1() = runBlocking {
        open()
        menu()
        shot("p116-menu-shortcuts")
        compose.onNodeWithTag("sc-Mute").performClick()
        waitUntil(1_000) { runBlocking { engine.get("mute", PropertyType.Flag) } == true }
        compose.onNodeWithTag("sc-Loop").performClick()
        waitUntil(1_000) { runBlocking { engine.get("loop-file", PropertyType.Text) } == "inf" }
        SystemClock.sleep(300)
        shot("p116-shortcuts-on")
    }

    @Test fun pictureMenu_US57() = runBlocking {
        open()
        menu()
        compose.onNodeWithTag("menu-picture").performScrollTo().performClick()
        compose.waitUntil(2_000) { shown("picture-panel") }
        compose.onNodeWithTag("pic-night").performClick()
        compose.onNodeWithTag("pic-mirror").performClick()
        SystemClock.sleep(500)
        val shaders = engine.get("glsl-shaders", PropertyType.Text)
        log("picture menu: glsl-shaders $shaders")
        assertTrue(shaders!!.contains("lanthorn-night") && shaders.contains("lanthorn-mirror"))
        shot("p116-picture-panel")
    }

    @Test fun fitAspectMenu_US53_US54() = runBlocking {
        open()
        menu()
        compose.onNodeWithTag("menu-fit-aspect").performScrollTo().performClick()
        compose.waitUntil(2_000) { shown("fit-panel") }
        compose.onNodeWithTag("aspect-4:3").performScrollTo().performClick()
        SystemClock.sleep(400)
        val aspect = engine.get("video-out-params/aspect", PropertyType.Real)
        log("aspect menu 4:3 -> video-out-params/aspect $aspect")
        assertEquals(4.0 / 3, aspect!!, 0.01)
        shot("p116-fit-panel")
    }

    @Test fun settingsInsidePlayer_US65_3() {
        open()
        menu()
        compose.onNodeWithTag("menu-player-settings").performScrollTo().performClick()
        compose.waitUntil(2_000) { shown("player-settings") }
        compose.onNodeWithTag("set-back-twice").performScrollTo().performClick()
        SystemClock.sleep(300)
        assertTrue("still playing", !engine.state.value.paused)
        shot("p116-settings-in-player")
    }

    private fun shown(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitUntil(ms: Long, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + ms
        while (!check()) {
            if (SystemClock.elapsedRealtime() > end) throw AssertionError("timed out after $ms ms")
            SystemClock.sleep(20)
        }
    }

    private fun copy(asset: String): Uri {
        val file = File(context.cacheDir, asset)
        if (!file.exists()) {
            InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        return Uri.fromFile(file)
    }

    private fun shot(name: String) {
        SystemClock.sleep(300)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun log(line: String) {
        android.util.Log.i("VideoMenuTest", line)
        val dir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "numbers.txt").appendText(line + "\n")
    }
}

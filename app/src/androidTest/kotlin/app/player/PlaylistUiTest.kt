package app.player

import android.content.Intent
import android.net.Uri
import android.os.StrictMode
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.player.core.data.playlist.Playlists
import app.player.core.engine.PlayerSessions
import app.player.core.testing.Ears
import app.player.core.testing.evidenceShot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** US-12.1 from the player: the menu's "Add to playlist" puts the playing file in a new playlist. */
@RunWith(AndroidJUnit4::class)
class PlaylistUiTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private val store = Playlists(context)
    private val name = "PL player test"

    @Before fun setUp() {
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        cleanUp()
    }

    @After fun cleanUp() = runBlocking {
        store.all().filter { it.first.name == name }.forEach { store.delete(it.first.id) }
    }

    private fun file(name: String): File = File(context.filesDir, name).also { f ->
        if (!f.exists()) instr.context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
    }

    private fun waitFor(timeoutMs: Long = 20_000, check: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (check()) return true
            SystemClock.sleep(100)
        }
        return false
    }

    private fun <T> retrying(step: () -> T): T? {
        repeat(3) { try { return step() } catch (_: StaleObjectException) { SystemClock.sleep(200) } }
        return null
    }

    @Ears("US-12.1")
    @Test fun us_12_1_add_the_playing_file_from_the_player_menu() {
        val uri = Uri.fromFile(file("clip420.mp4"))
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .setClassName(context, "app.player.feature.player.PlayerActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        assertTrue("never played", waitFor { PlayerSessions.current.value?.state?.value?.video != null })

        var opened = false
        repeat(3) {
            if (opened) return@repeat
            if (!device.hasObject(By.desc("More options"))) {
                device.click(device.displayWidth / 2, device.displayHeight / 2)
                device.wait(Until.hasObject(By.desc("More options")), 2000)
            }
            retrying { device.findObject(By.desc("More options"))?.click() }
            val entry = retrying {
                device.wait(Until.findObject(By.text("Add to playlist")), 1500)
                    ?: device.findObject(By.scrollable(true))?.scrollUntil(Direction.DOWN, Until.findObject(By.text("Add to playlist")))
            }
            if (entry != null && retrying { (device.findObject(By.text("Add to playlist")) ?: entry).click() } != null) {
                opened = device.wait(Until.hasObject(By.text("New playlist")), 3000)
            } else if (device.hasObject(By.scrollable(true))) {
                device.pressBack()
            }
        }
        assertTrue("Add to playlist panel did not open", opened)
        evidenceShot("player-add-to-playlist")
        retrying { device.findObject(By.text("New playlist")).click() }
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 3000)
        assertNotNull("name field", field)
        field.text = name
        retrying { device.findObject(By.text("Create")).click() }

        assertTrue("playlist not stored", waitFor(5_000) { runBlocking { store.all().any { it.first.name == name } } })
        val entries = runBlocking { store.all().single { it.first.name == name }.second }
        assertEquals(listOf(uri.toString()), entries.map { it.uri })
        assertEquals("clip420.mp4", entries.single().name)
        device.pressBack(); device.pressBack()
    }
}

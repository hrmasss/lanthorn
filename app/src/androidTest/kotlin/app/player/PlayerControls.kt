package app.player

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/**
 * Taps on the player's controls, for every app test class. A tap on the video shows the controls,
 * and they slide in: a tap at a control's place before it stops goes through to the video and
 * hides the controls again. So every tap here waits for its control to stand still first. Use
 * this instead of a local tap helper.
 */
class PlayerControls(private val device: UiDevice) {

    /** Shows the controls if hidden. True once [probe] is on screen and standing still. */
    fun show(probe: BySelector = By.desc(MORE)): Boolean {
        repeat(4) {
            if (!device.hasObject(probe)) {
                device.click(device.displayWidth / 2, device.displayHeight / 2)
                if (!device.wait(Until.hasObject(probe), 2_000)) return@repeat
            }
            if (still(probe) != null) return true
        }
        return false
    }

    /** The bounds of [sel] once two looks in a row agree, or null when it went away. */
    fun still(sel: BySelector): Rect? {
        var at = bounds(sel) ?: return null
        for (i in 1..10) {
            SystemClock.sleep(SETTLE_MS)
            val now = bounds(sel) ?: return null
            if (now == at) return now
            at = now
        }
        return at
    }

    /** Shows the controls and taps [sel] once it stands still. Fails when it never does. */
    fun tap(sel: BySelector) {
        repeat(5) {
            if (show(sel) && retrying { device.findObject(sel)?.click() } != null) return
        }
        throw AssertionError("control not found: $sel")
    }

    /** Opens the player's overflow menu and taps [label], scrolled to when below the fold. False when it never showed. */
    fun menu(label: String): Boolean {
        repeat(3) {
            if (show() && retrying { device.findObject(By.desc(MORE))?.click() } != null) {
                val entry = retrying {
                    device.wait(Until.findObject(By.text(label)), 1_500)
                        ?: device.findObject(By.scrollable(true))?.scrollUntil(Direction.DOWN, Until.findObject(By.text(label)))
                }
                // Found again for the tap: a recomposed menu leaves the old node stale.
                if (entry != null && retrying { (device.findObject(By.text(label)) ?: entry).click() } != null) return true
            }
            // Back with nothing open leaves the player, so close only a menu that did open.
            if (device.hasObject(By.scrollable(true))) device.pressBack()
        }
        return false
    }

    /** Runs [step], again when the overlay recomposed the node away between find and use. */
    fun <T> retrying(step: () -> T): T? {
        repeat(3) { try { return step() } catch (_: StaleObjectException) { SystemClock.sleep(200) } }
        return null
    }

    private fun bounds(sel: BySelector): Rect? = retrying { device.findObject(sel)?.visibleBounds }

    companion object {
        const val MORE = "More options"

        /** Between two looks at a control's place. */
        private const val SETTLE_MS = 150L
    }
}

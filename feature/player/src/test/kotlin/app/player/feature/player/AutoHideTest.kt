package app.player.feature.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The auto-hide timer on the Compose test clock, so the timing is exact (US-16.3, US-79). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoHideTest {
    @get:Rule val rule = createComposeRule()

    private var shown by mutableStateOf(true)
    private var touches by mutableIntStateOf(0)
    private var delayMs by mutableStateOf<Long?>(3_000)

    private fun start() {
        rule.mainClock.autoAdvance = false
        rule.setContent { AutoHideEffect(shown, touches, delayMs) { shown = false } }
        rule.mainClock.advanceTimeByFrame()
    }

    @Test fun `US-16_3 hides 3 s after showing, not before`() {
        start()
        rule.mainClock.advanceTimeBy(2_950)
        assertTrue(shown)
        rule.mainClock.advanceTimeBy(100)
        assertFalse(shown)
    }

    @Test fun `US-16_3 a touch restarts the 3 s`() {
        start()
        rule.mainClock.advanceTimeBy(2_000)
        rule.runOnUiThread {
            touches++
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(2_500)
        assertTrue("2.5 s after the last touch", shown)
        rule.mainClock.advanceTimeBy(600)
        assertFalse(shown)
    }

    @Test fun `US-16_3 never hides while paused (no delay)`() {
        delayMs = null
        start()
        rule.mainClock.advanceTimeBy(20_000)
        assertTrue(shown)
    }

    @Test fun `US-79_2 a new delay applies from the next showing`() {
        delayMs = 6_500
        start()
        rule.mainClock.advanceTimeBy(6_400)
        assertTrue(shown)
        rule.mainClock.advanceTimeBy(200)
        assertFalse(shown)
    }
}

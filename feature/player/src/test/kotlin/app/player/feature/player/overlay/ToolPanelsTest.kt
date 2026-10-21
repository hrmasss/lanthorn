package app.player.feature.player.overlay

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import app.player.core.designsystem.theme.LanthornPlayerTheme
import app.player.core.testing.Ears
import app.player.feature.player.abMs
import app.player.feature.player.tools.Chapter
import app.player.feature.player.tools.SleepTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToolPanelsTest {
    @get:Rule val rule = createComposeRule()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) =
        rule.setContent { LanthornPlayerTheme(dynamicColor = false) { content() } }

    @Ears("US-23.1")
    @Test fun `US-23_1 presets and slider set the speed in 0_05 steps`() {
        var speed = 1.0
        show { SpeedContent(1.0) { speed = it } }
        listOf("0.5", "0.75", "1", "1.25", "1.5", "2").forEach { rule.onNodeWithTag("speed-$it").assertIsDisplayed() }
        rule.onNodeWithTag("speed-1.5").performClick()
        assertEquals(1.5, speed, 1e-9)
        rule.onNodeWithTag("speed-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(3.33f) }
        assertEquals(3.35, speed, 1e-9)
        rule.onNodeWithTag("speed-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(0.1f) }
        assertEquals(0.25, speed, 1e-9)
    }

    @Ears("US-25.1")
    @Test fun `US-25_1 A then B, clear`() {
        val calls = mutableListOf<String>()
        show { AbContent(null, null, { calls += "a" }, { calls += "b" }, { calls += "clear" }) }
        rule.onNodeWithTag("ab-set-b").assertIsNotEnabled()
        rule.onNodeWithTag("ab-clear").assertIsNotEnabled()
        rule.onNodeWithTag("ab-set-a").performClick()
        assertEquals(listOf("a"), calls)
    }

    @Ears("US-25.1")
    @Test fun `US-25_1 with A set, B and clear work`() {
        val calls = mutableListOf<String>()
        show { AbContent(2_000, null, { calls += "a" }, { calls += "b" }, { calls += "clear" }) }
        rule.onNodeWithTag("ab-points").assertTextContains("A 0:02", substring = true)
        rule.onNodeWithTag("ab-set-b").assertIsEnabled().performClick()
        rule.onNodeWithTag("ab-clear").assertIsEnabled().performClick()
        assertEquals(listOf("b", "clear"), calls)
    }

    @Ears("US-26.1")
    @Test fun `US-26_1 presets start the timer, finish switch passes through`() {
        val started = mutableListOf<Pair<Int, Boolean>>()
        show { SleepContent(null, { m, f -> started += m to f }, {}) }
        listOf(15, 30, 45, 60).forEach { rule.onNodeWithTag("sleep-$it").assertIsDisplayed() }
        rule.onNodeWithTag("sleep-30").performClick()
        rule.onNodeWithTag("sleep-finish").performScrollTo().performClick()
        rule.onNodeWithTag("sleep-45").performScrollTo().performClick()
        assertEquals(listOf(30 to false, 45 to true), started)
    }

    @Ears("US-26.3")
    @Test fun `US-26_3 running timer shows time left and cancels`() {
        var cancelled = false
        val now = android.os.SystemClock.elapsedRealtime()
        show { SleepContent(SleepTimer.Running(now + 12 * 60_000L + 30_000, false), { _, _ -> }, { cancelled = true }) }
        rule.onNodeWithTag("sleep-left").assertTextContains("12:30", substring = true)
        rule.onNodeWithTag("sleep-cancel").performClick()
        assertEquals(true, cancelled)
    }

    @Ears("US-27")
    @Test fun `US-27 chapters list titles and times, tap picks`() {
        var picked: Chapter? = null
        val chapters = listOf(Chapter(0, "One", 0), Chapter(1, null, 65_000), Chapter(2, "Three", 120_000))
        show { ChaptersContent(chapters, 70_000) { picked = it } }
        rule.onNodeWithTag("chapter-0").assertTextContains("One", substring = true)
        rule.onNodeWithTag("chapter-1").assertTextContains("Chapter 2", substring = true)
        rule.onNodeWithTag("chapter-1").assertTextContains("1:05", substring = true)
        rule.onNodeWithTag("chapter-2").performClick()
        assertEquals(2, picked?.index)
    }

    @Ears("US-25.2", "US-27.1")
    @Test fun `US-25_2 and US-27_1 marks drawn on the seek bar`() {
        show {
            Controls(
                ControlsUi(
                    title = "t", playing = true, positionMs = 0, durationMs = 100_000, decoder = "HW", speed = null,
                    endTime = "1:40", showRemaining = false, status = null, subtitleButton = null, audioButton = null,
                    hasPrevious = false, hasNext = false, canLock = false, marks = listOf(0.3f, 0.6f), abA = 0.1f, abB = 0.2f,
                ),
                ControlsActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}),
            )
        }
        rule.onNode(hasTestTag("seek-marks")).assertIsDisplayed()
    }

    @Test fun `timer text and A-B parsing`() {
        assertEquals("15:00", SleepTimer.format(15 * 60_000L))
        assertEquals("0:01", SleepTimer.format(1))
        assertEquals("1:00:00", SleepTimer.format(3_600_000))
        assertEquals(12_500L, abMs("12.500"))
        assertNull(abMs("no"))
        assertNull(abMs(null))
    }

    @Suppress("unused") private val keep = SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)
}

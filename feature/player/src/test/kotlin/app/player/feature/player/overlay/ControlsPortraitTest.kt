package app.player.feature.player.overlay

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.player.core.designsystem.theme.LanthornPlayerTheme
import app.player.core.testing.Ears
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The top bar on a phone held upright: the title keeps the room, also at 200% font. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class ControlsPortraitTest {
    @get:Rule val rule = createComposeRule()

    private val actions = ControlsActions(
        back = {}, togglePause = {}, scrub = {}, seek = {}, toggleRemaining = {}, openDecoder = {},
        openSubtitles = {}, openAudio = {}, openMenu = {}, lock = {}, previous = {}, next = {}, rotate = {}, fit = {},
    )

    private val ui = ControlsUi(
        title = "With subtitles and a long name",
        playing = true,
        positionMs = 61_000,
        durationMs = 3_600_000,
        decoder = "HW+",
        speed = "1.5x",
        endTime = "1:00:00",
        showRemaining = false,
        status = StatusUi(87, "21:04"),
        subtitleButton = "Subtitles",
        audioButton = "Audio track",
        hasPrevious = true,
        hasNext = true,
        canLock = true,
    )

    private fun titleWidthAt(fontScale: Float): Float {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                LanthornPlayerTheme(dynamicColor = false) { Controls(ui, actions) }
            }
        }
        listOf("back", "title", "status", "speed-badge", "decoder-badge", "audio-button", "subtitle-button", "more")
            .forEach { rule.onNodeWithTag(it, useUnmergedTree = true).assertIsDisplayed() }
        val node = rule.onNodeWithTag("title", useUnmergedTree = true).fetchSemanticsNode()
        return with(rule.density) { node.size.width.toDp().value }
    }

    @Ears("US-16.1")
    @Test fun `US-16_1 portrait top bar leaves the title most of the width`() {
        val w = titleWidthAt(1f)
        assertTrue("title is $w dp of 411", w >= 250f)
    }

    @Ears("US-16.1")
    @Test fun `US-16_1 portrait title keeps its room at 200 percent font`() {
        val w = titleWidthAt(2f)
        assertTrue("title is $w dp of 411", w >= 250f)
    }
}

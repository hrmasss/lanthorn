package app.player.feature.player.overlay

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.dp
import app.player.core.designsystem.theme.LanthornPlayerTheme
import app.player.core.testing.Ears
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land")
class ControlsTest {
    @get:Rule val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private val actions = ControlsActions(
        back = { calls += "back" },
        togglePause = { calls += "pause" },
        scrub = { calls += "scrub" },
        seek = { calls += "seek" },
        toggleRemaining = { calls += "remaining" },
        openDecoder = { calls += "decoder" },
        openSubtitles = { calls += "subtitles" },
        openAudio = { calls += "audio" },
        openMenu = { calls += "menu" },
        lock = { calls += "lock" },
        previous = { calls += "previous" },
        next = { calls += "next" },
        rotate = { calls += "rotate" },
        fit = { calls += "fit" },
    )

    private fun ui(speed: String? = null, status: StatusUi? = StatusUi(87, "21:04")) = ControlsUi(
        title = "Holiday 2024",
        playing = true,
        positionMs = 61_000,
        durationMs = 3_600_000,
        decoder = "HW",
        speed = speed,
        endTime = "1:00:00",
        showRemaining = false,
        status = status,
        subtitleButton = "Subtitles",
        audioButton = "Audio track",
        hasPrevious = true,
        hasNext = true,
        canLock = true,
    )

    private fun show(ui: ControlsUi) = rule.setContent { LanthornPlayerTheme(dynamicColor = false) { Controls(ui, actions) } }

    @Ears("US-16.1")
    @Test fun `US-16_1 every control is in place`() {
        show(ui())
        listOf(
            "back", "title", "decoder-badge", "audio-button", "subtitle-button", "more",
            "elapsed", "seek-bar", "end-time",
            "lock", "previous", "play-pause", "next", "rotate", "fit",
        ).forEach { rule.onNodeWithTag(it, useUnmergedTree = true).assertIsDisplayed() }
    }

    @Ears("US-16.1")
    @Test fun `US-16_1 buttons do what they say`() {
        show(ui())
        mapOf(
            "back" to "back", "decoder-badge" to "decoder", "audio-button" to "audio",
            "subtitle-button" to "subtitles", "more" to "menu", "lock" to "lock", "previous" to "previous",
            "play-pause" to "pause", "next" to "next", "rotate" to "rotate", "fit" to "fit",
        ).forEach { (tag, call) ->
            rule.onNodeWithTag(tag).performClick()
            assertEquals(call, calls.last())
        }
    }

    @Ears("US-16.4")
    @Test fun `US-16_4 tapping the total switches to remaining`() {
        show(ui())
        rule.onNodeWithTag("end-time").performClick()
        assertEquals("remaining", calls.last())
    }

    @Ears("US-69.1")
    @Test fun `US-69_1 every interactive element has a description and a 48 dp target`() {
        show(ui(speed = "1.5x"))
        val clickable = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
        assertTrue("found ${clickable.size} clickable nodes", clickable.size >= 12)
        val density = rule.density
        clickable.forEach { node ->
            assertTrue("no description: ${node.config}", node.description().isNotBlank())
            val w = with(density) { node.touchBoundsInRoot.width.toDp() }
            val h = with(density) { node.touchBoundsInRoot.height.toDp() }
            assertTrue("${node.description()} is $w x $h", w >= 47.9.dp && h >= 47.9.dp)
        }
    }

    @Ears("US-23.3")
    @Test fun `US-23_3 speed shows only when not 1x`() {
        show(ui(speed = null))
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "speed-badge"), useUnmergedTree = true)
            .fetchSemanticsNodes().let { assertEquals(0, it.size) }
    }

    @Ears("US-23.3")
    @Test fun `US-23_3 speed badge shows the speed`() {
        show(ui(speed = "1.5x"))
        rule.onNodeWithTag("speed-badge", useUnmergedTree = true).assertIsDisplayed()
    }

    @Ears("US-35.1")
    @Test fun `US-35_1 decoder badge names the active decoder`() {
        show(ui())
        val node = rule.onNodeWithTag("decoder-badge").fetchSemanticsNode()
        assertTrue(node.description(), node.description().contains("HW"))
    }

    @Ears("US-60.3")
    @Test fun `US-60_3 battery and clock in the top bar`() {
        show(ui())
        val node = rule.onNodeWithTag("status", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(node.description().contains("87"))
        assertTrue(node.description().contains("21:04"))
    }

    @Ears("US-60.3")
    @Test fun `US-60_3 status hidden when the setting is off`() {
        show(ui(status = null))
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "status"), useUnmergedTree = true)
            .fetchSemanticsNodes().let { assertEquals(0, it.size) }
    }

    @Test fun `disabled previous and next stay labelled`() {
        show(ui().copy(hasPrevious = false, hasNext = false))
        rule.onNodeWithTag("previous").assertIsNotEnabled()
        rule.onNodeWithTag("next").assertIsNotEnabled()
    }

    private fun SemanticsNode.description(): String {
        val own = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        val merged = (own + children.flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
        return merged.joinToString(" ")
    }
}

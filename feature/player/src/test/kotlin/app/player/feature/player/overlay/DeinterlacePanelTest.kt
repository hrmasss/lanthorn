package app.player.feature.player.overlay

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import app.player.core.designsystem.theme.LanthornPlayerTheme
import app.player.core.engine.ActiveDecoder
import app.player.core.engine.DecoderMode
import app.player.core.testing.Ears
import app.player.feature.player.tools.Deinterlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeinterlacePanelTest {
    @get:Rule val rule = createComposeRule()

    private var picked: Deinterlace? = null
    private var switched: DecoderMode? = null

    private fun show(active: ActiveDecoder) = rule.setContent {
        LanthornPlayerTheme(dynamicColor = false) {
            DeinterlaceContent(Deinterlace.Auto, active, { picked = it }, { switched = it })
        }
    }

    @Ears("US-35.4")
    @Test fun `US-35_4 on HW the choices are disabled, one line names the decoder, the button switches to it`() {
        show(ActiveDecoder.HW)
        listOf("Auto", "On", "Off").forEach { rule.onNodeWithTag("deint-$it").assertIsNotEnabled() }
        rule.onNodeWithTag("deint-needs").assertTextContains("HW+", substring = true).assertTextContains("Now playing with HW", substring = true)
        rule.onNodeWithTag("deint-switch").assertTextContains("Switch to HW+").performClick()
        assertEquals(DecoderMode.HW_PLUS, switched)
        assertNull(picked)
    }

    @Ears("US-38.2")
    @Test fun `US-38_2 on HW+ the choices work and no switch is offered`() {
        show(ActiveDecoder.HW_PLUS)
        rule.onNodeWithTag("deint-On").assertIsEnabled().performClick()
        assertEquals(Deinterlace.On, picked)
        assertEquals(0, rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "deint-switch")).fetchSemanticsNodes().size)
    }

    @Ears("US-38.2")
    @Test fun `US-38_2 SW runs it too`() {
        show(ActiveDecoder.SW)
        rule.onNodeWithTag("deint-Off").assertIsEnabled().performClick()
        assertEquals(Deinterlace.Off, picked)
    }

    @Test fun `mpv text maps to the three modes`() {
        assertEquals(Deinterlace.On, Deinterlace.fromMpv("yes"))
        assertEquals(Deinterlace.Off, Deinterlace.fromMpv("no"))
        assertEquals(Deinterlace.Auto, Deinterlace.fromMpv("auto"))
        assertEquals(Deinterlace.Auto, Deinterlace.fromMpv(null))
    }
}

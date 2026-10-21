package app.player.feature.player.overlay

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.player.core.designsystem.theme.LanthornPlayerTheme
import app.player.core.engine.DecoderMode
import app.player.core.testing.Ears
import app.player.feature.player.settings.PlayerPrefs
import app.player.feature.player.settings.PlayerSettingsContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** US-69.1 beyond the overlay: the decoder picker and the Player settings sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h3000dp")
class TouchTargetsTest {
    @get:Rule val rule = createComposeRule()

    private fun check(content: @Composable () -> Unit, minimum: Int) {
        rule.setContent { LanthornPlayerTheme(dynamicColor = false) { content() } }
        val interactive = rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick) or SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress),
        ).fetchSemanticsNodes()
        assertTrue("found ${interactive.size}", interactive.size >= minimum)
        interactive.forEach { node ->
            assertTrue("no label: ${node.config}", node.label().isNotBlank())
            val w = with(rule.density) { node.touchBoundsInRoot.width.toDp() }
            val h = with(rule.density) { node.touchBoundsInRoot.height.toDp() }
            assertTrue("${node.label()} is $w x $h", w >= 47.9.dp && h >= 47.9.dp)
        }
    }

    @Ears("US-69.1")
    @Test fun `US-69_1 decoder picker`() = check({ DecoderContent(DecoderMode.AUTO, "HW") {} }, minimum = 4)

    @Ears("US-69.1")
    @Test fun `US-69_1 player settings sheet`() = check({ PlayerSettingsContent(PlayerPrefs(), {}) }, minimum = 7)

    private fun SemanticsNode.label(): String {
        val desc = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        val text = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        return (desc + text).joinToString(" ")
    }
}

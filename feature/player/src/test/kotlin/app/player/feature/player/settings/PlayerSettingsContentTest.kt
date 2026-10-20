package app.player.feature.player.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import app.player.core.designsystem.theme.LanthornPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerSettingsContentTest {
    @get:Rule val rule = createComposeRule()

    private var prefs by mutableStateOf(PlayerPrefs())

    private fun show() = rule.setContent {
        LanthornPlayerTheme(dynamicColor = false) { PlayerSettingsContent(prefs, { change -> prefs = change(prefs) }) }
    }

    @Test fun `US-79_1 auto-hide switch turns it off`() {
        show()
        rule.onNodeWithTag("set-auto-hide").assertIsOn().performClick()
        assertFalse(prefs.autoHide)
        rule.onNodeWithTag("set-auto-hide").assertIsOff()
    }

    @Test fun `US-79_1 delay slider sets 1 to 10 s in half seconds`() {
        show()
        rule.onNodeWithTag("set-auto-hide-delay").performSemanticsAction(SemanticsActions.SetProgress) { it(7_300f) }
        assertEquals(7_500L, prefs.autoHideDelayMs)
    }

    @Test fun `US-78 back twice switch`() {
        show()
        rule.onNodeWithTag("set-back-twice").performClick()
        assertTrue(prefs.backTwice)
    }

    @Test fun `US-60_4 keep status switch`() {
        show()
        rule.onNodeWithTag("set-keep-status").performClick()
        assertTrue(prefs.keepStatusVisible)
    }
}

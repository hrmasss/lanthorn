package app.player.feature.player.overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.player.core.designsystem.theme.LanthornPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MenusTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `US-32_1 every shortcut shows its state`() {
        val active = setOf(Shortcut.Loop, Shortcut.NightMode)
        var clicked: Shortcut? = null
        rule.setContent {
            LanthornPlayerTheme(dynamicColor = false) {
                ShortcutsGrid(Shortcut.entries) { s ->
                    ShortcutState(s in active, if (s == Shortcut.Shuffle) null else ({ clicked = s }))
                }
            }
        }
        assertEquals(13, Shortcut.entries.size)
        rule.onNodeWithTag("sc-Loop").assertIsOn()
        rule.onNodeWithTag("sc-NightMode").assertIsOn()
        rule.onNodeWithTag("sc-Mirror").assertIsOff().performClick()
        assertEquals(Shortcut.Mirror, clicked)
        rule.onNodeWithTag("sc-Shuffle").assertIsNotEnabled()
    }

    @Test fun `US-32_2 choose and reorder shortcuts`() {
        var shown by mutableStateOf(listOf(Shortcut.Speed, Shortcut.Loop, Shortcut.Mute))
        rule.setContent { LanthornPlayerTheme(dynamicColor = false) { ShortcutsSettings(shown) { shown = it } } }
        rule.onNodeWithTag("sc-up-Loop").performClick()
        assertEquals(listOf(Shortcut.Loop, Shortcut.Speed, Shortcut.Mute), shown)
        rule.onNodeWithTag("sc-on-Mute").performClick()
        assertEquals(listOf(Shortcut.Loop, Shortcut.Speed), shown)
        rule.onNodeWithTag("sc-on-Mirror").performScrollTo().performClick()
        assertEquals(listOf(Shortcut.Loop, Shortcut.Speed, Shortcut.Mirror), shown)
    }

    @Test fun `US-32_2 stored order round-trips and drops unknown ids`() {
        val list = listOf(Shortcut.Mirror, Shortcut.Speed)
        assertEquals(list, Shortcut.parse(Shortcut.format(list)))
        assertEquals(listOf(Shortcut.Speed), Shortcut.parse("Gone,Speed,Speed"))
        assertEquals(Shortcut.entries, Shortcut.parse(Shortcut.DEFAULT))
    }
}

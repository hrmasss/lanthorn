package app.player.feature.library

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.player.core.designsystem.theme.LanthornTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResumeButtonTest {
    @get:Rule val rule = createComposeRule()

    @Test fun us_08_2_resume_button_is_labelled_and_opens_the_last_video() {
        var taps = 0
        rule.setContent { LanthornTheme { ResumeButton { taps++ } } }
        rule.onNodeWithContentDescription("Resume").performClick()
        assertEquals(1, taps)
    }
}

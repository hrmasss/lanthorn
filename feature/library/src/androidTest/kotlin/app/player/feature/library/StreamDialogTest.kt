package app.player.feature.library

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.designsystem.theme.LanthornTheme
import app.player.feature.library.network.NetworkPrefs
import app.player.feature.library.network.StreamDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamDialogTest {
    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = NetworkPrefs(context)

    @Before fun clean() = runBlocking {
        prefs.setKeepHistory(true)
        prefs.clearHistory()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", "not a url"))
        }
    }

    @Test fun us_61_2_dialog_opens_with_a_url_from_the_clipboard() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("", "https://example.com/live/index.m3u8"))
        }
        rule.setContent { LanthornTheme { StreamDialog(onPlay = {}, onDismiss = {}) } }
        rule.onNodeWithTag("stream-url").assertTextContains("https://example.com/live/index.m3u8")
    }

    @Test fun clipboard_without_a_url_leaves_the_field_empty() {
        rule.setContent { LanthornTheme { StreamDialog(onPlay = {}, onDismiss = {}) } }
        assertEquals(0, rule.onAllNodesWithText("not a url", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun us_61_3_played_urls_are_kept_and_each_removable() {
        var played: String? = null
        rule.setContent { LanthornTheme { StreamDialog(onPlay = { played = it }, onDismiss = {}) } }
        rule.onNodeWithTag("stream-url").performTextClearance()
        rule.onNodeWithTag("stream-url").performTextInput("http://10.0.2.2:8000/clip.mp4")
        rule.onNodeWithTag("stream-play").performClick()
        assertEquals("http://10.0.2.2:8000/clip.mp4", played)
        rule.waitUntil(3_000) { runBlocking { prefs.history.first() }.isNotEmpty() }
        assertEquals(listOf("http://10.0.2.2:8000/clip.mp4"), runBlocking { prefs.history.first() })

        // The URL is also still in the field; look in the history list only.
        rule.onNode(hasText("http://10.0.2.2:8000/clip.mp4") and hasAnyAncestor(hasTestTag("stream-history"))).assertExists()
        rule.onNodeWithContentDescription("Remove http://10.0.2.2:8000/clip.mp4 from recent").performClick()
        rule.waitUntil(3_000) { runBlocking { prefs.history.first() }.isEmpty() }
    }

    @Test fun us_61_3_keep_none_records_nothing() {
        runBlocking { prefs.setKeepHistory(false) }
        rule.setContent { LanthornTheme { StreamDialog(onPlay = {}, onDismiss = {}) } }
        rule.onNodeWithTag("stream-url").performTextClearance()
        rule.onNodeWithTag("stream-url").performTextInput("http://10.0.2.2:8000/b.mp4")
        rule.onNodeWithTag("stream-play").performClick()
        Thread.sleep(500)
        assertEquals(emptyList<String>(), runBlocking { prefs.history.first() })
    }
}

package app.player.feature.library

import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.data.PlaybackPosition
import app.player.core.data.history.HistoryPrefs
import app.player.core.data.history.ResumePoints
import app.player.core.data.library.LibraryIndex
import app.player.core.data.library.LibraryVideo
import app.player.core.data.library.VideoSource
import app.player.core.designsystem.theme.LanthornTheme
import app.player.core.designsystem.theme.ThemeMode
import app.player.core.testing.Ears
import app.player.core.testing.evidenceShot
import app.player.feature.library.network.NetworkPrefs
import app.player.feature.library.settings.Appearance
import app.player.feature.library.settings.AppearancePrefs
import app.player.feature.library.settings.GeneralSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The General settings page: theme, language, history and the thumbnail cache. */
@RunWith(AndroidJUnit4::class)
class GeneralSettingsTest {
    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val started = mutableListOf<Intent>()

    @Before fun setUp() { runBlocking { GeneralSettings.reset(context) } }

    @After fun tearDown() { runBlocking {
        GeneralSettings.reset(context)
        clearAll()
    } }

    private suspend fun clearAll() {
        ResumePoints(context).clear()
        LibraryPrefsStore(context).clearSearches()
        NetworkPrefs(context).clearHistory()
    }

    private fun showPage() {
        rule.setContent {
            val base = LocalContext.current
            val recording = object : ContextWrapper(base) {
                override fun startActivity(intent: Intent) { started += intent }
                override fun startActivity(intent: Intent, options: Bundle?) { started += intent }
            }
            CompositionLocalProvider(LocalContext provides recording) {
                LanthornTheme { GeneralSettings.Content(null) }
            }
        }
    }

    private fun scrollTo(text: String) {
        rule.onNodeWithTag("settings-page").performScrollToNode(hasText(text))
    }

    private fun string(id: Int) = context.getString(id)

    @Ears("US-67.1")
    @Test fun us_67_1_light_dark_follow_system_by_default_and_pure_black() {
        assertEquals(Appearance(ThemeMode.System, dynamicColor = true, pureBlack = false), runBlocking { AppearancePrefs(context).appearance.first() })
        showPage()
        rule.onNodeWithText(string(R.string.setting_theme)).assertExists()
        rule.onNodeWithText(string(R.string.theme_system)).assertExists()
        rule.onNodeWithText(string(R.string.setting_dynamic_color)).assertExists()
        rule.onNodeWithText(string(R.string.setting_pure_black)).assertExists()
        evidenceShot("general-look")

        rule.onNodeWithText(string(R.string.setting_theme)).performClick()
        listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark).forEach {
            assertTrue(rule.onAllNodesWithText(string(it)).fetchSemanticsNodes().isNotEmpty())
        }
        evidenceShot("theme-choices")
        rule.onNodeWithText(string(R.string.theme_dark)).performClick()
        rule.waitUntil(5_000) { runBlocking { AppearancePrefs(context).appearance.first().mode } == ThemeMode.Dark }
        rule.onNodeWithText(string(R.string.setting_pure_black)).performClick()
        rule.waitUntil(5_000) { runBlocking { AppearancePrefs(context).appearance.first().pureBlack } }
    }

    @Ears("US-67.1")
    @Test fun us_67_1_dynamic_colour_is_used_and_pure_black_is_black() {
        val schemes = mutableMapOf<String, ColorScheme>()
        rule.setContent {
            Column {
                LanthornTheme(ThemeMode.Light, dynamicColor = true) { schemes["light"] = MaterialTheme.colorScheme }
                LanthornTheme(ThemeMode.Dark, dynamicColor = true) { schemes["dark"] = MaterialTheme.colorScheme }
                LanthornTheme(ThemeMode.Dark, dynamicColor = true, pureBlack = true) { schemes["black"] = MaterialTheme.colorScheme }
            }
        }
        rule.waitForIdle()
        assertEquals(dynamicLightColorScheme(context).primary, schemes["light"]!!.primary)
        assertEquals(dynamicDarkColorScheme(context).primary, schemes["dark"]!!.primary)
        assertEquals(Color.Black, schemes["black"]!!.background)
        assertEquals(Color.Black, schemes["black"]!!.surface)
    }

    @Ears("US-68.1")
    @Test fun us_68_1_language_opens_the_system_per_app_language_page() {
        showPage()
        rule.onNodeWithText(string(R.string.setting_language)).performClick()
        val intent = started.single()
        assertEquals(Settings.ACTION_APP_LOCALE_SETTINGS, intent.action)
        assertEquals(Uri.fromParts("package", context.packageName, null), intent.data)
    }

    @Ears("US-73.1")
    @Test fun us_73_1_clear_history_deletes_resume_points_marks_searches_and_urls_after_one_confirmation() {
        val uri = "content://media/external_primary/video/media/42"
        runBlocking {
            ResumePoints(context).put(PlaybackPosition(uri, 30_000, 600_000, System.currentTimeMillis()))
            LibraryPrefsStore(context).addSearch("holiday")
            NetworkPrefs(context).addToHistory("http://example.invalid/a.mp4", paused = false)
        }
        showPage()
        scrollTo(string(R.string.setting_clear_history))
        rule.onNodeWithText(string(R.string.setting_clear_history)).performClick()
        rule.onNodeWithText(string(R.string.confirm_clear_history)).assertExists()
        evidenceShot("clear-history-confirm")
        // Nothing goes before the confirmation.
        assertNotNull(runBlocking { ResumePoints(context).get(uri) })

        rule.onNodeWithText(string(R.string.action_clear)).performClick()
        rule.waitUntil(5_000) {
            runBlocking {
                ResumePoints(context).get(uri) == null && LibraryPrefsStore(context).prefs.first().recentSearches.isEmpty() &&
                    NetworkPrefs(context).history.first().isEmpty()
            }
        }
        runBlocking {
            // Last-played marks come from resume points (US-07.4): none left to mark.
            assertTrue(ResumePoints(context).all().isEmpty())
            assertEquals(emptyList<String>(), LibraryPrefsStore(context).prefs.first().recentSearches)
            assertEquals(emptyList<String>(), NetworkPrefs(context).history.first())
        }
    }

    @Ears("US-73.2")
    @Test fun us_73_2_pause_history_is_off_by_default_and_stops_search_and_url_history() {
        showPage()
        scrollTo(string(R.string.setting_pause_history))
        rule.onNodeWithText(string(R.string.setting_pause_history)).assertIsOff()
        rule.onNodeWithText(string(R.string.setting_pause_history)).performClick()
        rule.waitUntil(5_000) { runBlocking { HistoryPrefs(context).isPaused() } }
        rule.onNodeWithText(string(R.string.setting_pause_history)).assertIsOn()

        // The library's search box and the stream dialog record through these, as the app wires them.
        val store = LibraryPrefsStore(context)
        val paused = LibraryViewModel(
            LibraryIndex(MemoryDao(), object : VideoSource {
                override fun scan() = emptyList<LibraryVideo>()
            }),
            store, { emptyList() }, historyPaused = HistoryPrefs(context)::isPaused,
        )
        rule.runOnIdle { paused.addSearch("holiday") }
        runBlocking {
            NetworkPrefs(context).addToHistory("http://example.invalid/b.mp4", HistoryPrefs(context).isPaused())
        }
        Thread.sleep(500)
        rule.waitForIdle()
        runBlocking {
            assertEquals(emptyList<String>(), store.prefs.first().recentSearches)
            assertEquals(emptyList<String>(), NetworkPrefs(context).history.first())
        }
    }

    @Ears("US-73.3")
    @Test fun us_73_3_clearing_the_thumbnail_cache_deletes_it_and_reports_the_space() {
        val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
        repeat(3) { File(dir, "${it}_1.jpg").writeBytes(ByteArray(10_000)) }
        showPage()
        scrollTo(string(R.string.setting_clear_thumbnails))
        rule.onNodeWithText(string(R.string.setting_clear_thumbnails)).performClick()
        val expected = context.getString(R.string.thumbnails_cleared, Formatter.formatShortFileSize(context, 30_000))
        rule.waitUntil(5_000) { rule.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
        evidenceShot("thumbnails-cleared")
        assertTrue("thumbnails left: ${dir.list()?.toList()}", dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun reset_asks_once_before_resetting() {
        showPage()
        scrollTo(string(R.string.setting_reset))
        rule.onNodeWithText(string(R.string.setting_reset)).performClick()
        rule.onNodeWithText(string(R.string.confirm_reset)).assertExists()
        rule.onNodeWithText(string(android.R.string.cancel)).performClick()
        rule.onAllNodesWithText(string(R.string.confirm_reset)).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        assertNull(started.firstOrNull())
    }
}

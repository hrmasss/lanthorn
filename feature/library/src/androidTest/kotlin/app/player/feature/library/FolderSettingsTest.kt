package app.player.feature.library

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.designsystem.theme.LanthornTheme
import app.player.core.testing.Ears
import app.player.core.testing.evidenceShot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolderSettingsTest {
    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = LibraryPrefsStore(context)
    private val folder = "external_primary/Movies/Trips/"
    private val video = testVideo(1, "external_primary/Movies/", "Beach day.mp4")

    @After fun tearDown() = runBlocking {
        store.setHidden(folder, false)
        store.setHidden(video.uri, false)
    }

    @Ears("US-15.2")
    @Test fun us_15_2_hidden_items_are_listed_and_each_can_be_shown_again() {
        val dao = MemoryDao()
        val videos = listOf(video, testVideo(2, folder, "Hike.mp4"), testVideo(3, "external_primary/DCIM/"))
        dao.rows.value = videos.associateBy { it.id }
        runBlocking {
            store.setHidden(folder, true)
            store.setHidden(video.uri, true)
        }
        val (vm, _) = testViewModel(dao, store = store) { videos }
        vm.onAccess(MediaAccess.Full, false)
        rule.setContent {
            val state by vm.state.collectAsState()
            LanthornTheme { FolderSettingsScreen(state as? LibraryUiState.Content, vm, onBack = {}) }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("hidden-row").fetchSemanticsNodes().size == 2 }
        rule.onNodeWithText("Beach day.mp4").assertExists()
        rule.onNodeWithText("Movies/Trips", substring = true).assertExists()
        evidenceShot("hidden-items")

        rule.onAllNodesWithText(context.getString(R.string.action_unhide))[0].performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("hidden-row").fetchSemanticsNodes().size == 1 }
        rule.onAllNodesWithText(context.getString(R.string.action_unhide))[0].performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("hidden-row").fetchSemanticsNodes().isEmpty() }
        rule.onAllNodesWithTag("hidden-row").assertCountEquals(0)
        assertEquals(emptySet<String>(), runBlocking { store.prefs.first().filter.hidden })
    }
}

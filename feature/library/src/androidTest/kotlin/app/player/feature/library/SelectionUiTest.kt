package app.player.feature.library

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.data.library.LibraryVideo
import app.player.core.designsystem.theme.LanthornTheme
import app.player.core.engine.DecoderMode
import app.player.core.testing.Ears
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectionUiTest {
    @get:Rule val rule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun video(id: Long) = LibraryVideo(
        id, "content://media/external_primary/video/media/999$id", "external_primary", "external_primary/Movies/",
        null, "Clip $id.mp4", 1000, 60_000, 1920, 1080, id, "video/mp4",
    )

    @Ears("US-09.1")
    @Test fun us_09_1_long_press_selects_and_taps_then_toggle() {
        val videos = (1L..3L).map(::video)
        var selection by mutableStateOf(emptySet<String>())
        var opened = 0
        rule.setContent {
            LanthornTheme {
                LibraryItems(
                    emptyList(), videos, Layout.List,
                    RowContext(
                        ThumbnailLoader(context), { 0f }, emptySet(), { null }, {}, { opened++ },
                        selection = selection,
                        onSelect = { k -> selection = if (k in selection) selection - k else selection + k },
                    ),
                    rememberLazyListState(), rememberLazyGridState(),
                )
            }
        }
        rule.onAllNodesWithTag("video-row")[0].performTouchInput { longClick() }
        assertEquals(setOf("v:1"), selection)
        rule.onAllNodesWithTag("video-row")[0].assertIsSelected()
        rule.onAllNodesWithTag("video-row")[2].performClick()
        assertEquals(setOf("v:1", "v:3"), selection)
        rule.onAllNodesWithTag("video-row")[0].performClick()
        assertEquals(setOf("v:3"), selection)
        assertEquals("taps in selection mode never open the player", 0, opened)
    }

    @Ears("US-09.1")
    @Test fun us_09_1_selection_bar_shows_the_count() {
        rule.setContent {
            LanthornTheme {
                SelectionBar(2, listOf(video(1), video(2)), rememberFileActions {}, {}, {}, {})
            }
        }
        rule.onNodeWithTag("selection-count").assertExists()
        rule.onNodeWithText("2 selected").assertExists()
        rule.onNodeWithTag("selection-more").performClick()
        // Rename needs exactly one video.
        assertEquals(0, rule.onAllNodesWithTag("selection-rename").fetchSemanticsNodes().size)
    }

    @Ears("US-09.3")
    @Test fun us_09_3_play_with_passes_the_chosen_decoder() {
        var played: Pair<List<Long>, DecoderMode>? = null
        rule.setContent {
            LanthornTheme {
                SelectionBar(
                    2, listOf(video(1), video(2)), rememberFileActions {}, {}, {}, {},
                    onPlayWith = { vs, mode -> played = vs.map { it.id } to mode },
                )
            }
        }
        rule.onNodeWithTag("selection-more").performClick()
        rule.onNodeWithText("Play with HW").assertExists()
        rule.onNodeWithText("Play with HW+").assertExists()
        rule.onNodeWithText("Play with SW").performClick()
        assertEquals(listOf(1L, 2L) to DecoderMode.SW, played)
    }
}

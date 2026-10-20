package app.player.feature.library

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.data.library.LibraryVideo
import app.player.core.data.library.toFolders
import app.player.core.designsystem.theme.LanthornTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryItemsTest {
    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun video(id: Long, name: String, available: Boolean = true) = LibraryVideo(
        id, "content://media/external_primary/video/media/999999$id", "external_primary",
        "external_primary/Movies/", null, name, 1_400_000_000, 3_723_000, 1920, 1080, 1_700_000_000, "video/mp4",
        available,
    )

    private fun ctx(fields: Set<Field>) = RowContext(
        loader = ThumbnailLoader(context),
        fps = { 25f },
        fields = fields,
        volumeLabel = { null },
        onFolder = {},
        onVideo = {},
    )

    @Test fun us_02_1_name_without_extension_and_length_over_thumbnail() {
        rule.setContent {
            LanthornTheme {
                LibraryItems(emptyList(), listOf(video(1, "Episode 2.mp4")), Layout.List, ctx(emptySet()),
                    rememberLazyListState(), rememberLazyGridState())
            }
        }
        rule.onNodeWithText("Episode 2").assertExists()
        rule.onNodeWithText("1:02:03").assertExists()
        rule.onAllNodesWithTag("video-fields").assertCountEquals(0)
    }

    @Test fun us_02_2_fields_menu_choices_show_under_the_name() {
        rule.setContent {
            LanthornTheme {
                LibraryItems(emptyList(), listOf(video(1, "Film.mkv")), Layout.List, ctx(Field.entries.toSet()),
                    rememberLazyListState(), rememberLazyGridState())
            }
        }
        rule.waitUntil(2_000) {
            rule.onAllNodesWithText("25 fps", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        listOf("1.4 GB", "1920×1080", "25 fps", "MKV").forEach {
            rule.onNodeWithText(it, substring = true).assertExists()
        }
    }

    @Test fun us_02_3_rows_have_fixed_height_while_thumbnails_are_missing() {
        rule.setContent {
            LanthornTheme {
                LibraryItems(emptyList(), (1L..5L).map { video(it, "v$it.mp4") }, Layout.List, ctx(emptySet()),
                    rememberLazyListState(), rememberLazyGridState())
            }
        }
        // 128 dp wide 16:9 frame (72 dp) plus 8 dp padding above and below. The frame is sized
        // by its aspect ratio, not by the picture, so a thumbnail arriving cannot change it.
        rule.onAllNodesWithTag("video-row").onFirst().assertHeightIsEqualTo(88.dp)
    }

    @Test fun us_03_2_list_to_grid_within_one_frame() {
        var layout by mutableStateOf(Layout.List)
        val videos = (1L..20L).map { video(it, "v$it.mp4") }
        rule.setContent {
            LanthornTheme {
                LibraryItems(videos.toFolders(), videos, layout, ctx(emptySet()),
                    rememberLazyListState(), rememberLazyGridState())
            }
        }
        rule.onNodeWithTag("library-list").assertExists()
        rule.mainClock.autoAdvance = false
        layout = Layout.Grid
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("library-grid").assertExists()
        rule.onNodeWithTag("library-list").assertDoesNotExist()
    }

    @Test fun us_80_3_unavailable_video_is_marked() {
        rule.setContent {
            LanthornTheme {
                LibraryItems(emptyList(), listOf(video(1, "Gone.mp4", available = false)), Layout.List,
                    ctx(emptySet()), rememberLazyListState(), rememberLazyGridState())
            }
        }
        rule.onNodeWithText(context.getString(R.string.library_unavailable)).assertExists()
    }
}

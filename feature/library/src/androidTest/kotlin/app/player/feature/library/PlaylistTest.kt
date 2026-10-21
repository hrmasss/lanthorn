package app.player.feature.library

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.data.playlist.AddResult
import app.player.core.data.playlist.Playlists
import app.player.core.designsystem.theme.LanthornTheme
import app.player.core.testing.Ears
import app.player.core.testing.evidenceShot
import app.player.feature.library.settings.PlaylistsBackup
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Playlists (US-12): the store, the screens and the add dialog, on the real database. */
@RunWith(AndroidJUnit4::class)
class PlaylistTest {
    @get:Rule val rule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = Playlists(context)
    private val made = mutableListOf<Long>()

    /** Not in the library index, so never "present" as a library file. */
    private fun missing(n: Int) = "content://media/external_primary/video/media/9900$n" to "Gone $n.mp4"

    /** Uris the library does not index count as present (streams, shares). */
    private fun file(n: Int) = "file:///sdcard/Movies/PL/Clip $n.mp4" to "Clip $n.mp4"

    private fun create(name: String, videos: List<Pair<String, String>>): Long =
        runBlocking { store.create(name, videos).first }.also { made += it }

    @After fun cleanUp() = runBlocking {
        made.forEach { store.delete(it) }
        store.all().map { it.first }.filter { it.name.startsWith("PL test") }.forEach { store.delete(it.id) }
    }

    @Ears("US-12.1")
    @Test fun us_12_1_adds_in_order_and_skips_duplicates_with_a_count() = runBlocking {
        val id = create("PL test order", listOf(file(1), file(2)))
        val result = store.add(id, listOf(file(2), file(3), file(3), file(4)))
        assertEquals(AddResult(added = 2, skipped = 2), result)
        assertEquals(listOf(1, 2, 3, 4).map { file(it).first }, store.itemsNow(id).map { it.uri })
        assertEquals(
            "Added 2 videos to Trip. 2 were already in it.",
            addMessage(context.resources, "Trip", result),
        )
        assertEquals("Added 1 video to Trip.", addMessage(context.resources, "Trip", AddResult(1, 0)))
    }

    @Ears("US-12.1")
    @Test fun us_12_1_add_dialog_makes_a_playlist_then_adds_to_it() {
        val said = mutableListOf<String>()
        var open by mutableStateOf(true)
        var videos by mutableStateOf(listOf(file(1), file(2)))
        rule.setContent {
            LanthornTheme {
                if (open) AddToPlaylistDialog(videos, onDismiss = { open = false }, notify = { said += it })
            }
        }
        rule.onNodeWithTag("playlist-pick-new").performClick()
        rule.onNodeWithTag("playlist-name-field").performTextInput("PL test dialog")
        rule.onNodeWithTag("playlist-name-save").performClick()
        rule.waitUntil(5_000) { said.size == 1 && !open }
        assertEquals("Added 2 videos to PL test dialog.", said[0])

        // Again, from selection with one new and one already there: the existing playlist is listed.
        videos = listOf(file(2), file(3))
        open = true
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("playlist-pick-row").fetchSemanticsNodes().isNotEmpty() }
        evidenceShot("add-to-playlist-dialog")
        rule.onNodeWithText("PL test dialog").performClick()
        rule.waitUntil(5_000) { said.size == 2 }
        assertEquals("Added 1 video to PL test dialog. 1 was already in it.", said[1])
        val id = runBlocking { store.all().map { it.first }.single { it.name == "PL test dialog" }.id }
        assertEquals(listOf(1, 2, 3).map { file(it).first }, runBlocking { store.itemsNow(id).map { it.uri } })
    }

    @Ears("US-12.2")
    @Test fun us_12_2_drag_reorders_and_the_order_is_stored() {
        val id = create("PL test drag", listOf(file(1), file(2), file(3)))
        showPlaylist(id)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("playlist-item").fetchSemanticsNodes().size == 3 }
        val rowHeight = rule.onAllNodesWithTag("playlist-item").fetchSemanticsNodes()[0].size.height.toFloat()
        // Drag the first row's handle down past the third.
        rule.onAllNodesWithTag("playlist-drag")[0].performTouchInput {
            down(center)
            repeat(25) { moveBy(androidx.compose.ui.geometry.Offset(0f, rowHeight * 2.4f / 25)) }
            up()
        }
        rule.waitUntil(5_000) { runBlocking { store.itemsNow(id).map { it.uri } } == listOf(2, 3, 1).map { file(it).first } }
        evidenceShot("playlist-after-drag")
    }

    @Ears("US-12.2")
    @Test fun us_12_2_move_up_and_down_without_dragging() {
        val id = create("PL test move", listOf(file(1), file(2), file(3)))
        showPlaylist(id)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("playlist-item").fetchSemanticsNodes().size == 3 }
        val actions = rule.onAllNodesWithTag("playlist-item")[2].fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Move up"), actions.map { it.label })
        actions.single().action()
        rule.waitUntil(5_000) { runBlocking { store.itemsNow(id).map { it.uri } } == listOf(1, 3, 2).map { file(it).first } }
    }

    @Ears("US-12.2")
    @Test fun us_12_2_remove_rename_and_delete() {
        val id = create("PL test edit", listOf(file(1), file(2)))
        var back = false
        showPlaylist(id, onBack = { back = true })
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("playlist-item").fetchSemanticsNodes().size == 2 }
        rule.onAllNodesWithTag("playlist-item-more")[0].performClick()
        rule.onNodeWithTag("playlist-remove").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("playlist-item").fetchSemanticsNodes().size == 1 }
        assertEquals(listOf(file(2).first), runBlocking { store.itemsNow(id).map { it.uri } })

        rule.onNodeWithTag("playlist-more").performClick()
        rule.onNodeWithTag("playlist-rename").performClick()
        rule.onNodeWithTag("playlist-name-field").performTextReplacement("PL test edit renamed")
        rule.onNodeWithTag("playlist-name-save").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("PL test edit renamed").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag("playlist-more").performClick()
        rule.onNodeWithTag("playlist-delete").performClick()
        rule.onNodeWithText("Delete").performClick()
        rule.waitUntil(5_000) { back }
        assertTrue(runBlocking { store.all().map { it.first }.none { it.id == id } })
        assertTrue("entries go with the playlist", runBlocking { store.itemsNow(id).isEmpty() })
    }

    @Ears("US-12.3", "US-12.4")
    @Test fun us_12_3_4_plays_in_order_greys_and_skips_missing_files() {
        val id = create("PL test play", listOf(file(1), missing(1), file(2), missing(2), file(3)))
        var played: Pair<List<Uri>, Int>? = null
        val said = mutableListOf<String>()
        showPlaylist(id, onPlayQueue = { uris, i -> played = uris to i }, notify = { said += it })
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("playlist-item").fetchSemanticsNodes().size == 5 }
        rule.onAllNodesWithTag("playlist-missing", useUnmergedTree = true).assertCountEquals(2)
        evidenceShot("playlist-missing-greyed")

        rule.onNodeWithTag("playlist-play").performClick()
        assertEquals(listOf(1, 2, 3).map { file(it).first.toUri() } to 0, played)

        // A tap on a row starts there; on a missing row it says so and plays nothing.
        played = null
        rule.onAllNodesWithTag("playlist-item")[1].performClick()
        assertNull(played)
        assertEquals(listOf("File is missing"), said)
        rule.onAllNodesWithTag("playlist-item")[2].performClick()
        assertEquals(listOf(1, 2, 3).map { file(it).first.toUri() } to 1, played)
    }

    @Ears("US-12.4")
    @Test fun us_12_4_a_playlist_of_missing_files_cannot_play() {
        val id = create("PL test all missing", listOf(missing(1), missing(2)))
        showPlaylist(id)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("playlist-item").fetchSemanticsNodes().size == 2 }
        rule.onNodeWithTag("playlist-play").assertIsNotEnabled()
        assertNull(playlistQueue(runBlocking { store.itemsNow(id) }))
    }

    @Ears("US-12.1")
    @Test fun us_12_playlists_go_into_backups_and_come_back() = runBlocking {
        val id = create("PL test backup", listOf(file(1), file(3), file(2)))
        val exported = PlaylistsBackup.export(context)
        store.delete(id)
        assertTrue(store.itemsNow(id).isEmpty())
        assertTrue(PlaylistsBackup.import(context, exported) >= 1)
        assertEquals(listOf(1, 3, 2).map { file(it).first }, store.itemsNow(id).map { it.uri })
        assertFalse(store.itemsNow(id).any { !it.present })
    }

    private fun showPlaylist(
        id: Long,
        onPlayQueue: (List<Uri>, Int) -> Unit = { _, _ -> },
        onBack: () -> Unit = {},
        notify: (String) -> Unit = {},
    ) {
        rule.setContent {
            LanthornTheme {
                PlaylistScreen(id, null, ThumbnailLoader(context), onPlayQueue, onBack, notify)
            }
        }
    }
}

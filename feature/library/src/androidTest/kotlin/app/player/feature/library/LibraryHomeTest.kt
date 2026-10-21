package app.player.feature.library

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.data.library.LibraryIndex
import app.player.core.designsystem.theme.LanthornTheme
import app.player.core.testing.Ears
import app.player.core.testing.evidenceShot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The library's home list with an in-memory index: pull to rescan, view and sort choices, scroll, 1000 videos. */
@RunWith(AndroidJUnit4::class)
class LibraryHomeTest {
    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dao = MemoryDao()
    private val store = LibraryPrefsStore(context)

    @After fun tearDown() { runBlocking {
        store.setViewMode(ViewMode.Folders)
        store.setSort(Sort())
    } }

    private fun show(vm: LibraryViewModel, index: LibraryIndex) {
        val loaders = testLoaders(context, index)
        rule.setContent { Home(vm, loaders) }
    }

    @androidx.compose.runtime.Composable
    private fun Home(vm: LibraryViewModel, loaders: Pair<ThumbnailLoader, VideoProbe>) {
        val state by vm.state.collectAsState()
        LanthornTheme {
            LibraryHome(state, vm, loaders, {}, { _, _ -> }, { _, _ -> }, {}, {}, {}, {}, {}, {}, {})
        }
    }

    @Ears("US-05.5")
    @Test fun us_05_5_pull_down_rescans_with_a_progress_indicator_until_the_scan_ends() {
        val videos = (1L..20L).map { testVideo(it) }
        dao.rows.value = videos.associateBy { it.id }
        val release = CountDownLatch(1)
        val scans = AtomicInteger()
        val (vm, index) = testViewModel(dao) {
            scans.incrementAndGet()
            release.await(20, TimeUnit.SECONDS)
            videos
        }
        vm.onAccess(MediaAccess.Full, false)
        show(vm, index)
        rule.onNodeWithTag("library-list").assertExists()
        rule.onAllNodesWithTag("library-scanning").assertCountEquals(0)

        rule.onNodeWithTag("library-list").performTouchInput { swipeDown(startY = top + 20f, endY = bottom - 20f, durationMillis = 600) }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("library-scanning").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("the pull started a scan", 1, scans.get())
        evidenceShot("pull-to-refresh-scanning")
        // Still scanning: the indicator stays.
        SystemClock.sleep(1_000)
        rule.onNodeWithTag("library-scanning").assertExists()

        release.countDown()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("library-scanning").fetchSemanticsNodes().isEmpty() }
        assertEquals(false, index.scanning.value)
    }

    @Ears("US-03.1")
    @Test fun us_03_1_all_videos_lists_every_video_flat_and_the_choice_is_stored() {
        val videos = listOf(
            testVideo(1, "external_primary/Movies/"), testVideo(2, "external_primary/Movies/Trips/"),
            testVideo(3, "external_primary/DCIM/Camera/"),
        )
        dao.rows.value = videos.associateBy { it.id }
        runBlocking { store.setViewMode(ViewMode.Folders) }
        val (vm, index) = testViewModel(dao, store = store) { videos }
        vm.onAccess(MediaAccess.Full, false)
        show(vm, index)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("folder-row").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag("toggle-view-mode").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("video-row").fetchSemanticsNodes().size == 3 }
        rule.onAllNodesWithTag("folder-row").assertCountEquals(0)
        listOf("Clip 1", "Clip 2", "Clip 3").forEach { rule.onNodeWithText(it).assertExists() }
        evidenceShot("all-videos")

        // Kept across restarts: written to the library's preferences file on disk.
        assertEquals(ViewMode.AllVideos, runBlocking { store.prefs.first().viewMode })
        assertTrue("view mode not on disk", storedFile().readBytes().decodeToString().contains("AllVideos"))
    }

    @Ears("US-04.3")
    @Test fun us_04_3_the_sort_choice_is_stored_on_disk() {
        val videos = (1L..3L).map { testVideo(it) }
        dao.rows.value = videos.associateBy { it.id }
        runBlocking { store.setSort(Sort()) }
        val (vm, index) = testViewModel(dao, store = store) { videos }
        vm.onAccess(MediaAccess.Full, false)
        show(vm, index)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("open-sort").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag("open-sort").performClick()
        rule.onNodeWithTag("sort-Length").performClick()
        rule.waitUntil(5_000) { runBlocking { store.prefs.first().sort.key } == SortKey.Length }
        val text = storedFile().readBytes().decodeToString()
        assertTrue("sort not on disk", text.contains("sort_key") && text.contains("Length"))
    }

    /** The player is another activity; coming back, or the library being recreated meanwhile, keeps the position. */
    @OptIn(ExperimentalTestApi::class)
    @Ears("US-03.3")
    @Test fun us_03_3_scroll_position_survives_leaving_for_the_player() {
        val videos = (1L..80L).map { testVideo(it) }
        dao.rows.value = videos.associateBy { it.id }
        val (vm, index) = testViewModel(dao, LibraryPrefs(viewMode = ViewMode.AllVideos)) { videos }
        vm.onAccess(MediaAccess.Full, false)
        val loaders = testLoaders(context, index)
        val restore = StateRestorationTester(rule)
        restore.setContent { Home(vm, loaders) }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("video-row").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag("library-list").performScrollToNode(hasText("Clip 51"))
        rule.onNodeWithText("Clip 51").assertExists()
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithText("Clip 51").assertExists()
        rule.onAllNodesWithText("Clip 1").assertCountEquals(0)
        evidenceShot("scroll-restored")
    }

    /**
     * NF-9 on the emulator: a cold scan of 1000 videos (empty index, fake source) to the list on
     * screen, timed. Only the visible rows are composed, so only their thumbnails load. The
     * MediaStore query on a real phone is the phone-only part (perf/phone-only.md).
     */
    @Ears("NF-9")
    @Test fun nf_9_a_cold_scan_of_1000_videos_lists_them_lazily() {
        val videos = (1L..1000L).map { testVideo(it, "external_primary/Movies/${it % 40}/") }
        val (vm, index) = testViewModel(dao, LibraryPrefs(viewMode = ViewMode.AllVideos)) { videos }
        show(vm, index)
        rule.waitForIdle()
        val start = SystemClock.elapsedRealtime()
        vm.onAccess(MediaAccess.Full, false)
        vm.refresh()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("video-row").fetchSemanticsNodes().isNotEmpty() &&
                (vm.state.value as? LibraryUiState.Content)?.videoCount == 1000
        }
        val took = SystemClock.elapsedRealtime() - start
        val composed = rule.onAllNodesWithTag("video-row").fetchSemanticsNodes().size
        Log.i("NF-9", "1000 videos listed $took ms after a cold scan started; $composed rows composed")
        evidenceShot("1000-videos")
        assertTrue("rows are not lazy: $composed composed", composed in 1..40)
        assertWithin("NF-9 cold scan to list of 1000", took, 1_500)
    }

    private fun storedFile() = File(context.filesDir, "datastore/library.preferences_pb")
}

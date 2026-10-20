package app.player.feature.library

import android.Manifest
import app.player.core.data.library.IndexDiff
import app.player.core.data.library.LibraryDao
import app.player.core.data.library.LibraryIndex
import app.player.core.data.library.LibraryVideo
import app.player.core.data.library.VideoExtra
import app.player.core.data.library.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun video(id: Long, folder: String) = LibraryVideo(
    id, "content://media/external_primary/video/media/$id", "external_primary", folder, null, "v$id.mp4",
    1, 1000, 1920, 1080, id, "video/mp4",
)

private class MemoryDao : LibraryDao {
    val rows = MutableStateFlow<Map<Long, LibraryVideo>>(emptyMap())
    override fun observeAll() = rows.map { it.values.toList() }
    override suspend fun all() = rows.value.values.toList()
    override suspend fun upsert(videos: List<LibraryVideo>) { rows.value = rows.value + videos.associateBy { it.id } }
    override suspend fun delete(ids: List<Long>) { rows.value = rows.value - ids.toSet() }
    override suspend fun apply(diff: IndexDiff) = super.apply(diff)
    override suspend fun extra(id: Long): VideoExtra? = null
    override suspend fun putExtra(extra: VideoExtra) = Unit
}

private fun source(list: () -> List<LibraryVideo>) = object : VideoSource {
    override fun scan() = list()
    override fun mountedVolumes() = setOf("external_primary")
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val main = StandardTestDispatcher()
    private val dao = MemoryDao()
    private var onDisk = listOf<LibraryVideo>()

    @Before fun setUp() = Dispatchers.setMain(main)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm() = LibraryViewModel(
        LibraryIndex(dao, source { onDisk }, StandardTestDispatcher(testScheduler), { 0 }),
        null,
        { listOf(Volume("external_primary", "Internal shared storage"), Volume("1234-abcd", "SD card")) },
        StandardTestDispatcher(testScheduler),
    )

    @Test fun `loading until the screen reports access`() = runTest(main) {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(LibraryUiState.Loading, vm.state.value)
    }

    @Test fun `us_71_1 no access shows the access screen and never scans`() = runTest(main) {
        onDisk = listOf(video(1, "external_primary/Movies/"))
        val vm = vm()
        vm.onAccess(MediaAccess.None, permanentlyDenied = false)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(LibraryUiState.NeedsAccess(false), vm.state.value)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test fun `us_71_4 permanently denied keeps a way to grant`() = runTest(main) {
        val vm = vm()
        vm.onAccess(MediaAccess.None, permanentlyDenied = true)
        advanceUntilIdle()
        assertEquals(LibraryUiState.NeedsAccess(true), vm.state.value)
    }

    @Test fun `nf_1 cached index shows before any scan`() = runTest(main) {
        dao.rows.value = mapOf(1L to video(1, "external_primary/Movies/"))
        val vm = vm()
        vm.onAccess(MediaAccess.Full, false)
        advanceUntilIdle()
        val s = vm.state.value as LibraryUiState.Content
        assertEquals(1, s.videoCount)
        assertFalse(s.scanned)
    }

    @Test fun `us_01_3 refresh picks up files added and removed while closed`() = runTest(main) {
        dao.rows.value = mapOf(1L to video(1, "external_primary/Movies/"))
        onDisk = listOf(video(2, "external_primary/Movies/"), video(3, "external_primary/Clips/"))
        val vm = vm()
        vm.onAccess(MediaAccess.Full, false)
        vm.refresh()
        advanceUntilIdle()
        val s = vm.state.value as LibraryUiState.Content
        assertEquals(2, s.videoCount)
        assertEquals(listOf("Clips", "Movies"), s.folders.map { it.name })
        assertTrue(s.scanned)
    }

    @Test fun `us_01_4 empty after a scan names the scanned storage`() = runTest(main) {
        val vm = vm()
        vm.onAccess(MediaAccess.Full, false)
        vm.refresh()
        advanceUntilIdle()
        val s = vm.state.value as LibraryUiState.Content
        assertTrue(s.folders.isEmpty())
        assertTrue(s.scanned)
        assertEquals(listOf("Internal shared storage", "SD card"), s.volumes.map { it.label })
        assertEquals(null, s.volumeLabel("external_primary"))
        assertEquals("SD card", s.volumeLabel("1234-abcd"))
    }

    @Test fun `partial access is flagged`() = runTest(main) {
        val vm = vm()
        vm.onAccess(MediaAccess.Partial, false)
        advanceUntilIdle()
        assertTrue((vm.state.value as LibraryUiState.Content).partial)
    }

    @Test fun `media changes are batched into one scan`() = runTest(main) {
        var scans = 0
        val vm = LibraryViewModel(
            LibraryIndex(dao, source { scans++; onDisk }, StandardTestDispatcher(testScheduler), { 0 }),
            null,
            { emptyList() },
            StandardTestDispatcher(testScheduler),
        )
        vm.onAccess(MediaAccess.Full, false)
        repeat(5) { vm.onMediaChanged() }
        advanceUntilIdle()
        assertEquals(1, scans)
    }
}

class MediaAccessTest {
    private fun access(sdk: Int, vararg granted: String) = mediaAccess(sdk) { it in granted }

    @Test fun `android 14 plus offers partial access`() {
        assertEquals(
            listOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            mediaPermissions(34).toList(),
        )
        assertEquals(MediaAccess.Partial, access(34, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        assertEquals(MediaAccess.Full, access(36, Manifest.permission.READ_MEDIA_VIDEO))
        assertEquals(MediaAccess.None, access(36))
    }

    @Test fun `android 13 asks for videos only`() {
        assertEquals(listOf(Manifest.permission.READ_MEDIA_VIDEO), mediaPermissions(33).toList())
        assertEquals(MediaAccess.None, access(33, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
    }

    @Test fun `android 12 and older use the storage permission`() {
        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), mediaPermissions(29).toList())
        assertEquals(MediaAccess.Full, access(32, Manifest.permission.READ_EXTERNAL_STORAGE))
    }
}

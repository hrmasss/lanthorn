package app.player.core.data.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

fun video(id: Long, folder: String = "external_primary/Movies/", name: String = "v$id.mp4", modified: Long = id) =
    LibraryVideo(
        id = id, uri = "content://media/external_primary/video/media/$id", volume = folder.substringBefore('/'),
        folder = folder, path = null, name = name, sizeBytes = 1, durationMs = 1000, width = 1920, height = 1080,
        modifiedSec = modified, mimeType = "video/mp4",
    )

class FakeDao(initial: List<LibraryVideo> = emptyList()) : LibraryDao {
    val rows = MutableStateFlow(initial.associateBy { it.id })
    var transactions = 0

    override fun observeAll(): Flow<List<LibraryVideo>> = MutableStateFlow(rows.value.values.toList())
    override suspend fun all() = rows.value.values.toList()
    override suspend fun upsert(videos: List<LibraryVideo>) { rows.value = rows.value + videos.associateBy { it.id } }
    override suspend fun delete(ids: List<Long>) { rows.value = rows.value - ids.toSet() }
    override suspend fun apply(diff: IndexDiff) { transactions++; super.apply(diff) }
}

class LibraryIndexTest {
    @Test fun `us_01_3 scan adds new videos and drops removed ones`() = runTest {
        val dao = FakeDao(listOf(video(1), video(2)))
        val index = LibraryIndex(dao, { listOf(video(2), video(3)) }, StandardTestDispatcher(testScheduler), { 0 })

        val result = index.refresh()

        assertEquals(setOf(2L, 3L), dao.rows.value.keys)
        assertEquals(1, result.changed)
        assertEquals(1, result.removed)
        assertEquals(2, result.count)
    }

    @Test fun `scan that finds the cached state writes nothing`() = runTest {
        val dao = FakeDao(listOf(video(1), video(2)))
        val index = LibraryIndex(dao, { listOf(video(2), video(1)) }, StandardTestDispatcher(testScheduler), { 0 })

        index.refresh()

        assertEquals(0, dao.transactions)
    }

    @Test fun `changed metadata is rewritten`() = runTest {
        val dao = FakeDao(listOf(video(1)))
        val renamed = video(1, name = "renamed.mkv")
        LibraryIndex(dao, { listOf(renamed) }, StandardTestDispatcher(testScheduler), { 0 }).refresh()
        assertEquals(renamed, dao.rows.value[1])
    }

    @Test fun `scanning flag is up only while scanning`() = runTest {
        var seenDuring = false
        lateinit var index: LibraryIndex
        index = LibraryIndex(FakeDao(), { seenDuring = index.scanning.value; emptyList() },
            StandardTestDispatcher(testScheduler), { 0 })
        index.refresh()
        assertTrue(seenDuring)
        assertFalse(index.scanning.first())
    }

    @Test fun `scan of 1000 videos into an empty index`() = runTest {
        val dao = FakeDao()
        val all = (1L..1000L).map { video(it, folder = "external_primary/F${it % 20}/") }
        val result = LibraryIndex(dao, { all }, StandardTestDispatcher(testScheduler), { 0 }).refresh()
        assertEquals(1000, dao.rows.value.size)
        assertEquals(1000, result.changed)
    }
}

class FoldersTest {
    @Test fun `us_01_1 folders hold their direct videos, count and newest cover`() {
        val videos = listOf(
            video(1, "external_primary/Movies/"),
            video(2, "external_primary/Movies/", modified = 50),
            video(3, "external_primary/Movies/Trips/"),
            video(4, "1234-ABCD/DCIM/Camera/"),
        )
        val folders = videos.toFolders().associateBy { it.key }

        assertEquals(3, folders.size)
        val movies = folders.getValue("external_primary/Movies/")
        assertEquals("Movies", movies.name)
        assertEquals(2, movies.videoCount)
        assertEquals(2L, movies.cover.id)
        val trips = folders.getValue("external_primary/Movies/Trips/")
        assertEquals("Trips", trips.name)
        assertEquals("Movies/Trips", trips.relativePath)
        assertEquals("1234-ABCD", folders.getValue("1234-ABCD/DCIM/Camera/").volume)
    }

    @Test fun `volume root folder has an empty name`() {
        val f = listOf(video(1, folderKey("external_primary", null))).toFolders().single()
        assertEquals("", f.name)
        assertEquals("", f.relativePath)
    }
}

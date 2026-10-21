package app.player.feature.library

import app.player.core.data.library.LibraryVideo
import app.player.core.data.library.toFolders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun v(id: Long, folder: String, available: Boolean = true) = LibraryVideo(
    id, "content://media/external_primary/video/media/$id", "external_primary", folder, null, "v$id.mp4",
    1, 1000, 1920, 1080, id, "video/mp4", available,
)

class SelectionTest {
    private val a = "external_primary/A/"
    private val b = "external_primary/B/"
    private val all = listOf(v(1, a), v(2, a), v(3, b), v(4, b), v(5, b, available = false))
    private val folders = all.toFolders().sortedBy { it.name }

    @Test fun `us_09_2 selected videos in the order shown`() {
        val shown = listOf(all[3], all[0], all[2])
        val picked = selectedVideos(setOf("v:1", "v:4"), emptyList(), shown, all)
        assertEquals(listOf(4L, 1L), picked.map { it.id })
    }

    @Test fun `us_09_4 a selected folder is all its videos`() {
        val picked = selectedVideos(setOf(SelectionKey.of(folders[0])), folders, emptyList(), all)
        assertEquals(listOf(1L, 2L), picked.map { it.id })
    }

    @Test fun `folder and one of its videos count once, unavailable left out`() {
        val picked = selectedVideos(setOf(SelectionKey.of(folders[1]), "v:3"), folders, listOf(all[2]), all)
        assertEquals(listOf(3L, 4L), picked.map { it.id })
    }

    @Test fun `us_10_1 rename keeps the extension`() {
        assertEquals("Holiday.mkv", renamed("clip.mkv", "Holiday"))
        assertEquals("Holiday.mkv", renamed("clip.mkv", " Holiday.mkv "))
        assertEquals("Holiday.mp4.mkv", renamed("clip.mkv", "Holiday.mp4"))
        assertNull(renamed("clip.mkv", "clip"))
        assertNull(renamed("clip.mkv", "  "))
        assertNull(renamed("clip.mkv", "a/b"))
    }
}

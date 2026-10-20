package app.player.feature.library

import app.player.core.data.library.LibraryVideo
import app.player.core.data.library.toFolders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun v(
    id: Long, name: String, folder: String = "external_primary/Movies/",
    size: Long = 1, ms: Long = 1000, w: Int = 1920, h: Int = 1080, modified: Long = 0,
) = LibraryVideo(id, "content://media/external_primary/video/media/$id", "external_primary", folder, null,
    name, size, ms, w, h, modified, "video/mp4")

class LibraryQueryTest {
    @Test fun `us_04_2 names sort naturally`() {
        val names = listOf("Episode 10.mp4", "episode 2.mp4", "Episode 1.mp4", "Épisode 3.mp4", "Episode 02b.mp4")
        val sorted = names.mapIndexed { i, n -> v(i.toLong(), n) }.sortedFor(Sort()).map { it.name }
        assertEquals(listOf("Episode 1.mp4", "episode 2.mp4", "Episode 02b.mp4", "Épisode 3.mp4", "Episode 10.mp4"), sorted)
    }

    @Test fun `us_04_1 every key and direction`() {
        val a = v(1, "a.mp4", size = 300, ms = 10, w = 640, h = 360, modified = 3)
        val b = v(2, "b.mp4", size = 100, ms = 30, w = 3840, h = 2160, modified = 1)
        val c = v(3, "c.mp4", size = 200, ms = 20, w = 1920, h = 1080, modified = 2)
        val all = listOf(a, b, c)
        fun order(key: SortKey, desc: Boolean = false) = all.sortedFor(Sort(key, desc)).map { it.id }
        assertEquals(listOf(1L, 2L, 3L), order(SortKey.Name))
        assertEquals(listOf(3L, 2L, 1L), order(SortKey.Name, true))
        assertEquals(listOf(2L, 3L, 1L), order(SortKey.Date))
        assertEquals(listOf(2L, 3L, 1L), order(SortKey.Size))
        assertEquals(listOf(1L, 3L, 2L), order(SortKey.Length))
        assertEquals(listOf(2L, 3L, 1L), order(SortKey.Resolution, true))
    }

    @Test fun `us_04_1 folders follow the same key`() {
        val videos = listOf(
            v(1, "x.mp4", "external_primary/Big/", size = 900),
            v(2, "y.mp4", "external_primary/Small/", size = 10),
            v(3, "z.mp4", "external_primary/Small/", size = 10),
        )
        val folders = videos.toFolders()
        assertEquals(listOf("Small", "Big"), folders.sortedFor(Sort(SortKey.Size), videos).map { it.name })
        assertEquals(listOf("Small", "Big"), folders.sortedFor(Sort(SortKey.Name, true), videos).map { it.name })
    }

    @Test fun `us_05_1 excluded folder takes its subfolders with it`() {
        val f = LibraryFilter(excludedFolders = setOf("external_primary/Movies/"))
        assertFalse(f.accepts(v(1, "a.mp4", "external_primary/Movies/")))
        assertFalse(f.accepts(v(2, "a.mp4", "external_primary/Movies/Trips/")))
        assertTrue(f.accepts(v(3, "a.mp4", "external_primary/MoviesOld/")))
    }

    @Test fun `us_05_4 extension switched off`() {
        val f = LibraryFilter(disabledExtensions = setOf("avi"))
        assertFalse(f.accepts(v(1, "old.AVI")))
        assertTrue(f.accepts(v(2, "new.mkv")))
    }

    @Test fun `us_15_1 hidden folder and hidden video`() {
        val video = v(1, "a.mp4")
        assertFalse(LibraryFilter(hidden = setOf(video.uri)).accepts(video))
        assertFalse(LibraryFilter(hidden = setOf("external_primary/Movies/")).accepts(video))
        assertTrue(LibraryFilter(hidden = setOf("external_primary/Movies/")).accepts(v(2, "b.mp4", "external_primary/Movies/Sub/")))
    }

    @Test fun `us_06_1 search ignores case and accents and finds folders`() {
        val videos = listOf(v(1, "Café Paris.mp4"), v(2, "Other.mkv", "external_primary/Cafe Days/"))
        val r = search("CAFE", videos.toFolders(), videos)
        assertEquals(listOf(1L), r.videos.map { it.id })
        assertEquals(listOf("Cafe Days"), r.folders.map { it.name })
        assertTrue(search("  ", videos.toFolders(), videos).videos.isEmpty())
    }

    @Test fun `us_06_1 search of 1000 videos well under 100 ms`() {
        val videos = (1L..1000L).map { v(it, "Holiday clip number $it, été édition.mp4", "external_primary/F${it % 20}/") }
        val folders = videos.toFolders()
        repeat(20) { search("ete", folders, videos) } // warm up the JIT
        val start = System.nanoTime()
        val runs = 50
        repeat(runs) { search("clip 99", folders, videos) }
        val perSearchMs = (System.nanoTime() - start) / 1e6 / runs
        println("search over 1000 videos: %.2f ms per keystroke".format(perSearchMs))
        assertTrue("took $perSearchMs ms", perSearchMs < 100)
    }

    @Test fun `us_06_2 recent searches keep 10, newest first, no duplicates`() {
        var recent = emptyList<String>()
        (1..12).forEach { recent = recent.withSearch("q$it") }
        recent = recent.withSearch("Q5")
        assertEquals(10, recent.size)
        assertEquals("Q5", recent.first())
        assertEquals(1, recent.count { it.equals("q5", true) })
        assertEquals(recent, recent.withSearch("   "))
    }
}

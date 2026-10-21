package app.player.feature.subtitles

import app.player.core.testing.Ears
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class OpenSubtitlesTest {
    @Ears("US-46.1")
    @Test fun `hash is size plus the little-endian sums of the first and last 64 KiB`() {
        val f = File.createTempFile("hash", ".bin")
        // 131072 bytes: first chunk all 0x01, last chunk all 0x02.
        f.writeBytes(ByteArray(65536) { 1 } + ByteArray(65536) { 2 })
        val word1 = 0x0101010101010101L
        val word2 = 0x0202020202020202L
        val expected = 131072L + 8192 * word1 + 8192 * word2
        assertEquals("%016x".format(expected), RandomAccessFile(f, "r").use { OpenSubtitles.hash(it) })
        f.delete()
    }

    @Ears("US-46.1")

    @Test fun `files under 64 KiB have no hash`() {
        val f = File.createTempFile("small", ".bin").apply { writeBytes(ByteArray(100)) }
        assertNull(RandomAccessFile(f, "r").use { OpenSubtitles.hash(it) })
        f.delete()
    }

    @Ears("US-46.1")
    @Test fun `results list language, rating and release, hash matches first (US-46_1)`() {
        val json = """{"data":[
          {"attributes":{"language":"en","release":"Clip.WEB","ratings":6.0,"download_count":10,"moviehash_match":false,"files":[{"file_id":1,"file_name":"a.srt"}]}},
          {"attributes":{"language":"fr","release":"Clip.BluRay","ratings":8.5,"download_count":5,"moviehash_match":true,"files":[{"file_id":2,"file_name":"b.srt"}]}},
          {"attributes":{"language":"de","release":"","ratings":9.0,"download_count":1,"files":[]}}
        ]}"""
        val r = OpenSubtitles.parseResults(json)
        assertEquals(listOf(2L, 1L), r.map { it.fileId })
        assertEquals("fr", r[0].language)
        assertEquals(8.5, r[0].rating, 0.0)
        assertEquals("Clip.BluRay", r[0].release)
    }

    @Ears("US-46.2")
    @Test fun `saved beside a writable local video, else in app storage (US-46_2)`() {
        val root = kotlin.io.path.createTempDirectory().toFile()
        val app = File(root, "app")
        val videos = File(root, "videos").apply { mkdirs() }
        assertEquals(videos, OnlineSearch.saveDir("file", File(videos, "a.mkv").path, app))
        assertEquals(app, OnlineSearch.saveDir("content", "/external/video/media/7", app))
        videos.setWritable(false)
        try {
            assertEquals(app, OnlineSearch.saveDir("file", File(videos, "a.mkv").path, app))
        } finally {
            videos.setWritable(true)
            root.deleteRecursively()
        }
    }
}

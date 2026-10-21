package app.player.feature.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleMatchTest {
    private fun t(id: Long, lang: String? = null, external: Boolean = false, default: Boolean = false) =
        Track(id, "sub", null, lang, "subrip", external, null, default, false, null)

    @Test fun `names that start with the video's base name match (US-39_1)`() {
        assertTrue(SubtitleMatch.matches("Movie.mkv", "Movie.srt"))
        assertTrue(SubtitleMatch.matches("Movie.mkv", "movie.en.srt"))
        assertTrue(SubtitleMatch.matches("Movie.mkv", "Movie.forced.eng.ASS"))
        assertTrue(SubtitleMatch.matches("Movie.mkv", "Movie_fre.vtt"))
        assertFalse(SubtitleMatch.matches("Movie.mkv", "Movie2.srt"))
        assertFalse(SubtitleMatch.matches("Movie.mkv", "Movie.mp4"))
        assertFalse(SubtitleMatch.matches("Movie.mkv", "Other.srt"))
    }

    @Test fun `every listed format is picked, vobsub through its idx (US-39_1)`() {
        val files = listOf("a.mkv", "a.srt", "a.ass", "a.ssa", "a.vtt", "a.idx", "a.sub", "a.sup", "a.ttml", "a.txt", "b.srt")
        assertEquals(listOf("a.ass", "a.idx", "a.srt", "a.ssa", "a.sup", "a.ttml", "a.txt", "a.vtt"), SubtitleMatch.pick("a.mkv", files))
        // MicroDVD .sub without an .idx stays.
        assertEquals(listOf("c.sub"), SubtitleMatch.pick("c.avi", listOf("c.sub", "c.avi")))
    }

    @Test fun `language comes from the name after the base`() {
        assertEquals("en", SubtitleMatch.languageOf("Movie.mkv", "Movie.en.srt"))
        assertEquals("fre", SubtitleMatch.languageOf("Movie.mkv", "Movie_fre.srt"))
        assertEquals("eng", SubtitleMatch.languageOf("Movie.mkv", "Movie.eng.sdh.srt"))
        assertNull(SubtitleMatch.languageOf("Movie.mkv", "Movie.srt"))
    }

    @Test fun `auto track is the preferred language, else first external, else default embedded (US-39_2)`() {
        val tracks = listOf(t(1, "eng", default = true), t(2, "fre"), t(3, "ita", external = true), t(4, "spa", external = true))
        assertEquals(2L, SubtitleMatch.autoTrack(tracks, setOf("fr", "fra", "fre"))?.id)
        assertEquals(3L, SubtitleMatch.autoTrack(tracks, setOf("de", "deu", "ger"))?.id)
        assertEquals(1L, SubtitleMatch.autoTrack(tracks.take(2), emptySet())?.id)
        assertNull(SubtitleMatch.autoTrack(listOf(t(5, "eng")), emptySet()))
    }
}

class SubtitleDiscoveryTest {
    @Test fun `a folder grant covers the folder and everything below it`() {
        assertTrue(SubtitleDiscovery.covers("primary:Movies", "primary:Movies"))
        assertTrue(SubtitleDiscovery.covers("primary:Movies", "primary:Movies/Series/S01"))
        assertTrue(SubtitleDiscovery.covers("primary:", "primary:Download"))
        assertFalse(SubtitleDiscovery.covers("primary:Movies", "primary:Movies2"))
        assertFalse(SubtitleDiscovery.covers("primary:Movies", "1A2B-3C4D:Movies"))
    }

    @Test fun `media store volume names map to storage roots`() {
        assertEquals("primary", SubtitleDiscovery.volumeId("external_primary"))
        assertEquals("1A2B-3C4D", SubtitleDiscovery.volumeId("1a2b-3c4d"))
    }
}

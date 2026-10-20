package app.player.feature.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {
    @Test fun `numbers sort as numbers and case is ignored (US-13_3)`() {
        val names = listOf("Episode 10.mkv", "episode 2.mkv", "Episode 1.mkv", "Episode 02b.mkv", "Alpha.mp4", "episode 01.mkv")
        assertEquals(
            listOf("Alpha.mp4", "Episode 1.mkv", "episode 01.mkv", "episode 2.mkv", "Episode 02b.mkv", "Episode 10.mkv"),
            names.sortedWith(NaturalOrder),
        )
    }

    @Test fun `a prefix sorts first`() {
        assertEquals(listOf("Film", "Film 2"), listOf("Film 2", "Film").sortedWith(NaturalOrder))
    }
}

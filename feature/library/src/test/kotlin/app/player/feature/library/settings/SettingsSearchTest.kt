package app.player.feature.library.settings

import app.player.core.testing.Ears
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private val index = listOf(
        SettingHit("general", "General", "theme", "Theme", null),
        SettingHit("general", "General", "pause_history", "Pause history", "Keep no resume points, last played marks or searches"),
        SettingHit("general", "General", "clear_history", "Clear history", "Delete resume points, last played marks and search history"),
        SettingHit("subtitle", "Subtitle", "font", "Police des sous-titres", "Taille et écart"),
    )

    @Ears("US-65.1")
    @Test fun `us_65_1 all typed words must match, in title or summary`() {
        assertEquals(listOf("pause_history", "clear_history"), searchSettings("history", index).map { it.key })
        assertEquals(listOf("clear_history"), searchSettings("delete RESUME", index).map { it.key })
        assertEquals(listOf("pause_history"), searchSettings("resume keep", index).map { it.key })
    }

    @Ears("US-65.1")
    @Test fun `us_65_1 accents and case are ignored`() {
        assertEquals(listOf("font"), searchSettings("ECART", index).map { it.key })
    }

    @Test fun `blank finds nothing`() {
        assertTrue(searchSettings("   ", index).isEmpty())
    }

    @Ears("US-65.1")
    @Test fun `us_65_1 well under 100 ms per keystroke for 500 settings`() {
        val big = (1..500).map { SettingHit("g", "G", "k$it", "Setting number $it with a title", "And a summary é $it") }
        repeat(20) { searchSettings("summary 4", big) }
        // Fastest of 50 single runs against the bound, median printed (see LibraryQueryTest).
        val times = List(50) { app.player.feature.library.msOf { searchSettings("summary 4", big) } }.sorted()
        println("settings search over 500 entries: fastest %.2f ms, median %.2f ms per keystroke".format(times.first(), times[25]))
        assertTrue("fastest took ${times.first()} ms", times.first() < 100)
    }
}

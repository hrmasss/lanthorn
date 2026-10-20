package app.player.core.settingsapi

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PreferencesJsonTest {
    @Test fun `us_66 every value type survives export and import`() {
        val prefs = mutablePreferencesOf(
            booleanPreferencesKey("b") to true,
            intPreferencesKey("i") to 7,
            longPreferencesKey("l") to (1L shl 40),
            floatPreferencesKey("f") to 1.5f,
            stringPreferencesKey("s") to "dark",
            stringSetPreferencesKey("ss") to setOf("a", "b"),
            stringPreferencesKey("history") to "kept out",
        )
        // Through text, as a backup file goes.
        val json = JSONObject(PreferencesJson.export(prefs, skip = setOf("history")).toString())
        assertFalse(json.has("history"))

        val back = mutablePreferencesOf()
        assertEquals(6, PreferencesJson.import(json, back))
        assertEquals(true, back[booleanPreferencesKey("b")])
        assertEquals(7, back[intPreferencesKey("i")])
        assertEquals(1L shl 40, back[longPreferencesKey("l")])
        assertEquals(1.5f, back[floatPreferencesKey("f")])
        assertEquals("dark", back[stringPreferencesKey("s")])
        assertEquals(setOf("a", "b"), back[stringSetPreferencesKey("ss")])
    }

    @Test fun `malformed entries are skipped, the rest imports`() {
        val json = JSONObject("""{"ok":{"t":"b","v":true},"bad":{"t":"i","v":"x"},"odd":{"t":"?","v":1},"flat":3}""")
        val back = mutablePreferencesOf()
        assertEquals(1, PreferencesJson.import(json, back))
        assertEquals(true, back[booleanPreferencesKey("ok")])
    }
}

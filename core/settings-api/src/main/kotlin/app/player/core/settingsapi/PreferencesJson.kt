package app.player.core.settingsapi

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * A DataStore Preferences file as JSON for backups (US-66.1), and back. Each value keeps its type
 * as `{"t": "b", "v": true}`, so an import writes the same key type the app reads.
 * [skip] leaves keys out, such as history kept in a settings file.
 */
object PreferencesJson {
    fun export(prefs: Preferences, skip: Set<String> = emptySet()): JSONObject {
        val out = JSONObject()
        for ((key, value) in prefs.asMap()) {
            if (key.name in skip) continue
            val typed = when (value) {
                is Boolean -> "b" to value
                is Int -> "i" to value
                is Long -> "l" to value
                is Float -> "f" to value.toDouble()
                is Double -> "d" to value
                is String -> "s" to value
                is Set<*> -> "ss" to JSONArray(value.map { it.toString() })
                else -> continue
            }
            out.put(key.name, JSONObject().put("t", typed.first).put("v", typed.second))
        }
        return out
    }

    /** Writes every typed value in [data] into [prefs]. Malformed entries are skipped. */
    fun import(data: JSONObject, prefs: MutablePreferences, skip: Set<String> = emptySet()): Int {
        var count = 0
        for (name in data.keys()) {
            if (name in skip) continue
            val entry = data.optJSONObject(name) ?: continue
            runCatching {
                when (entry.getString("t")) {
                    "b" -> prefs[booleanPreferencesKey(name)] = entry.getBoolean("v")
                    "i" -> prefs[intPreferencesKey(name)] = entry.getInt("v")
                    "l" -> prefs[longPreferencesKey(name)] = entry.getLong("v")
                    "f" -> prefs[floatPreferencesKey(name)] = entry.getDouble("v").toFloat()
                    "d" -> prefs[doublePreferencesKey(name)] = entry.getDouble("v")
                    "s" -> prefs[stringPreferencesKey(name)] = entry.getString("v")
                    "ss" -> {
                        val arr = entry.getJSONArray("v")
                        prefs[stringSetPreferencesKey(name)] = (0 until arr.length()).map { arr.getString(it) }.toSet()
                    }
                    else -> return@runCatching
                }
                count++
            }
        }
        return count
    }
}

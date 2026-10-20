package app.player.feature.audio

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.audioStore by preferencesDataStore("audio")

/** The Audio settings group's values (US-47.2, US-48.2, US-48.3). */
data class AudioPrefs(
    /** ISO 639-1 code, or empty for none. */
    val preferredLanguage: String = "",
    val defaultDelayMs: Long = 0,
    val bluetoothDelayMs: Long = 0,
)

internal class AudioPrefsStore(context: Context) {
    val store = context.applicationContext.audioStore

    val prefs: Flow<AudioPrefs> = store.data.map {
        AudioPrefs(
            preferredLanguage = it[LANGUAGE].orEmpty(),
            defaultDelayMs = it[DEFAULT_DELAY] ?: 0,
            bluetoothDelayMs = it[BLUETOOTH_DELAY] ?: 0,
        )
    }

    suspend fun setLanguage(code: String) = store.edit { it[LANGUAGE] = code }
    suspend fun setDefaultDelay(ms: Long) = store.edit { it[DEFAULT_DELAY] = AudioController.snapDelay(ms) }
    suspend fun setBluetoothDelay(ms: Long) = store.edit { it[BLUETOOTH_DELAY] = AudioController.snapDelay(ms) }
    suspend fun reset() = store.edit { it.clear() }

    companion object {
        val LANGUAGE = stringPreferencesKey("preferred_language")
        val DEFAULT_DELAY = longPreferencesKey("default_delay_ms")
        val BLUETOOTH_DELAY = longPreferencesKey("bluetooth_delay_ms")
    }
}

/** Languages offered for "preferred audio language", as ISO 639-1 codes. */
internal val LANGUAGES = listOf(
    "en", "es", "fr", "de", "it", "pt", "ru", "ja", "ko", "zh", "ar", "hi", "bn", "ur", "fa", "tr",
    "pl", "nl", "sv", "cs", "el", "he", "th", "vi", "id", "ms", "ro", "hu", "uk", "ta", "te",
)

/**
 * mpv's `alang` for a language: the two-letter code plus both three-letter forms, since files
 * tag tracks with any of them ("fr", "fra", "fre"). Empty for none.
 */
internal fun alangFor(code: String): String {
    if (code.isBlank()) return ""
    val terminology = runCatching { Locale(code).isO3Language }.getOrDefault("")
    return listOf(code, terminology, BIBLIOGRAPHIC[code].orEmpty()).filter { it.isNotEmpty() }.distinct().joinToString(",")
}

/** Where ISO 639-2/B differs from 639-2/T. */
private val BIBLIOGRAPHIC = mapOf(
    "zh" to "chi", "fr" to "fre", "de" to "ger", "nl" to "dut", "cs" to "cze", "el" to "gre", "fa" to "per",
    "ro" to "rum", "sk" to "slo", "hy" to "arm", "ka" to "geo", "mk" to "mac", "ms" to "may", "my" to "bur",
    "sq" to "alb", "eu" to "baq", "is" to "ice", "cy" to "wel", "bo" to "tib",
)

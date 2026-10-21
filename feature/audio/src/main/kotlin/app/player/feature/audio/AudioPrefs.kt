package app.player.feature.audio

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.audioStore by preferencesDataStore("audio")

/** The Audio settings group's values (US-47.2, US-48.2, US-48.3, US-49.1), and the equalizer (US-50.3). */
data class AudioPrefs(
    /** ISO 639-1 code, or empty for none. */
    val preferredLanguage: String = "",
    val defaultDelayMs: Long = 0,
    val bluetoothDelayMs: Long = 0,
    val volumeBoost: Boolean = true,
    internal val eq: EqSaved = EqSaved(),
)

internal class AudioPrefsStore(context: Context) {
    val store = context.applicationContext.audioStore

    val prefs: Flow<AudioPrefs> = store.data.map {
        AudioPrefs(
            preferredLanguage = it[LANGUAGE].orEmpty(),
            defaultDelayMs = it[DEFAULT_DELAY] ?: 0,
            bluetoothDelayMs = it[BLUETOOTH_DELAY] ?: 0,
            volumeBoost = it[VOLUME_BOOST] ?: true,
            eq = EqSaved(
                on = it[EQ_ON] ?: false,
                preampDb = it[EQ_PREAMP] ?: 0f,
                preset = it[EQ_PRESET]?.let { name -> EqPreset.entries.firstOrNull { p -> p.name == name } } ?: EqPreset.Flat,
                customDb = it[EQ_CUSTOM]?.split(',')?.mapNotNull(String::toFloatOrNull)?.takeIf { c -> c.size == Eq.BANDS }
                    ?: List(Eq.BANDS) { 0f },
            ),
        )
    }

    suspend fun setLanguage(code: String) = store.edit { it[LANGUAGE] = code }
    suspend fun setDefaultDelay(ms: Long) = store.edit { it[DEFAULT_DELAY] = AudioController.snapDelay(ms) }
    suspend fun setBluetoothDelay(ms: Long) = store.edit { it[BLUETOOTH_DELAY] = AudioController.snapDelay(ms) }
    suspend fun setVolumeBoost(on: Boolean) = store.edit { it[VOLUME_BOOST] = on }
    suspend fun setEq(eq: EqSaved) = store.edit {
        it[EQ_ON] = eq.on
        it[EQ_PREAMP] = eq.preampDb
        it[EQ_PRESET] = eq.preset.name
        it[EQ_CUSTOM] = eq.customDb.joinToString(",")
    }
    suspend fun reset() = store.edit { it.clear() }

    companion object {
        val LANGUAGE = stringPreferencesKey("preferred_language")
        val DEFAULT_DELAY = longPreferencesKey("default_delay_ms")
        val BLUETOOTH_DELAY = longPreferencesKey("bluetooth_delay_ms")
        val VOLUME_BOOST = booleanPreferencesKey("volume_boost")
        val EQ_ON = booleanPreferencesKey("eq_on")
        val EQ_PREAMP = floatPreferencesKey("eq_preamp_db")
        val EQ_PRESET = stringPreferencesKey("eq_preset")
        val EQ_CUSTOM = stringPreferencesKey("eq_custom_db")
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

package app.player.feature.subtitles

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale

/** Subtitle loading settings (US-39): show automatically, preferred language, extra folder. */
data class SubtitlePrefs(
    val showAutomatically: Boolean = true,
    /** ISO 639-1 code, or empty for none. */
    val preferredLanguage: String = "",
    /** A folder the viewer picked where subtitles are also looked for, as a tree uri. */
    val subtitleFolder: String = "",
    /** Subtitle gestures (US-45), each on by default. */
    val gestures: SubtitleGestureSwitches = SubtitleGestureSwitches(),
)

internal class SubtitlePrefsStore(context: Context) {
    val store = context.applicationContext.subtitleStore

    val prefs: Flow<SubtitlePrefs> = store.data.map {
        SubtitlePrefs(
            it[AUTO] ?: true,
            it[LANGUAGE].orEmpty(),
            it[FOLDER].orEmpty(),
            SubtitleGestureSwitches(it[GESTURE_SWIPE] ?: true, it[GESTURE_DRAG] ?: true, it[GESTURE_PINCH] ?: true),
        )
    }

    suspend fun current(): SubtitlePrefs = prefs.first()
    suspend fun setShowAutomatically(on: Boolean) = store.edit { it[AUTO] = on }
    suspend fun setLanguage(code: String) = store.edit { it[LANGUAGE] = code }
    suspend fun setGestures(g: SubtitleGestureSwitches) = store.edit {
        it[GESTURE_SWIPE] = g.swipe
        it[GESTURE_DRAG] = g.drag
        it[GESTURE_PINCH] = g.pinch
    }
    suspend fun setFolder(tree: Uri?) = store.edit { if (tree == null) it.remove(FOLDER) else it[FOLDER] = tree.toString() }

    suspend fun reset() = store.edit {
        it.remove(AUTO)
        it.remove(LANGUAGE)
        it.remove(FOLDER)
        it.remove(GESTURE_SWIPE)
        it.remove(GESTURE_DRAG)
        it.remove(GESTURE_PINCH)
    }

    companion object {
        val AUTO = booleanPreferencesKey("show_automatically")
        val LANGUAGE = stringPreferencesKey("preferred_language")
        val FOLDER = stringPreferencesKey("subtitle_folder")
        val GESTURE_SWIPE = booleanPreferencesKey("gesture_swipe")
        val GESTURE_DRAG = booleanPreferencesKey("gesture_drag")
        val GESTURE_PINCH = booleanPreferencesKey("gesture_pinch")
    }
}

/** Languages offered for "preferred subtitle language", as ISO 639-1 codes. */
internal val SUBTITLE_LANGUAGES = listOf(
    "en", "es", "fr", "de", "it", "pt", "ru", "ja", "ko", "zh", "ar", "hi", "bn", "ur", "fa", "tr",
    "pl", "nl", "sv", "cs", "el", "he", "th", "vi", "id", "ms", "ro", "hu", "uk", "ta", "te",
)

/** Every code form of a language ("fr", "fra", "fre"), lower case, to match track and file tags. */
internal fun languageForms(code: String): Set<String> {
    if (code.isBlank()) return emptySet()
    val terminology = runCatching { Locale(code).isO3Language }.getOrDefault("")
    return setOf(code, terminology, BIBLIOGRAPHIC[code].orEmpty()).filter { it.isNotEmpty() }.toSet()
}

/** Where ISO 639-2/B differs from 639-2/T. */
private val BIBLIOGRAPHIC = mapOf(
    "zh" to "chi", "fr" to "fre", "de" to "ger", "nl" to "dut", "cs" to "cze", "el" to "gre", "fa" to "per",
    "ro" to "rum", "sk" to "slo", "hy" to "arm", "ka" to "geo", "mk" to "mac", "ms" to "may", "my" to "bur",
    "sq" to "alb", "eu" to "baq", "is" to "ice", "cy" to "wel", "bo" to "tib",
)

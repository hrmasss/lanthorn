package app.player.core.engine

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The Decoder settings group (US-36). Per-file choices live in core/data's per_file_choice table. */
data class DecoderPrefs(
    val preferSoftware: Boolean = false,
    /** Lower-case extension without the dot, for example "mkv". */
    val byExtension: Map<String, DecoderMode> = emptyMap(),
)

/**
 * Picks the decoder for a file (US-36.1): the one [remembered] for the file, then its extension,
 * then "prefer software", then hardware. [remembered] is null when nothing is stored or the
 * remember switch is off.
 */
object DecoderPolicy {
    fun pick(uri: String, prefs: DecoderPrefs, remembered: DecoderMode? = null): DecoderMode {
        remembered?.let { return it }
        extensionOf(uri)?.let { ext -> prefs.byExtension[ext]?.let { return it } }
        return if (prefs.preferSoftware) DecoderMode.SW else DecoderMode.AUTO
    }

    fun extensionOf(uri: String): String? {
        val name = uri.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return if (dot in 1 until name.length - 1) name.substring(dot + 1).lowercase() else null
    }
}

private val Context.decoderStore: DataStore<Preferences> by preferencesDataStore("decoder")

/** Stores [DecoderPrefs]. */
class DecoderSettings(context: Context) {
    private val store = context.applicationContext.decoderStore

    val prefs: Flow<DecoderPrefs> = store.data.map { p ->
        DecoderPrefs(
            preferSoftware = p[PREFER_SW] ?: false,
            byExtension = p.asMap().entries.mapNotNull { (k, v) ->
                if (!k.name.startsWith(EXT)) return@mapNotNull null
                val mode = (v as? String)?.let { runCatching { DecoderMode.valueOf(it) }.getOrNull() }
                mode?.let { k.name.removePrefix(EXT) to it }
            }.toMap(),
        )
    }

    suspend fun setPreferSoftware(on: Boolean) {
        store.edit { it[PREFER_SW] = on }
    }

    /** Null or AUTO clears the extension's setting. */
    suspend fun setForExtension(extension: String, mode: DecoderMode?) {
        store.edit { p ->
            val key = stringPreferencesKey(EXT + extension.removePrefix(".").lowercase())
            if (mode == null || mode == DecoderMode.AUTO) p.remove(key) else p[key] = mode.name
        }
    }

    suspend fun reset() {
        store.edit { it.clear() }
    }

    /** The raw preferences, for a backup file. */
    suspend fun snapshot(): Preferences = store.data.first()

    /** Writes a restored backup into the store. */
    suspend fun restore(block: (MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }

    private companion object {
        val PREFER_SW = booleanPreferencesKey("prefer_software")
        const val EXT = "ext:"
    }
}

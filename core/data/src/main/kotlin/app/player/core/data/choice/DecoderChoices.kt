package app.player.core.data.choice

import android.content.Context
import app.player.core.engine.DecoderMode
import app.player.core.engine.DecoderPolicy
import app.player.core.engine.DecoderSettings
import kotlinx.coroutines.flow.first

/**
 * The decoder for a file, from the per-file choice and the Decoder settings (US-36).
 * The player calls [choose] before opening and [rememberManualChoice] when the viewer picks one.
 */
class DecoderChoices(context: Context) {
    private val perFile = PerFileChoices(context)
    private val settings = DecoderSettings(context)

    suspend fun choose(uri: String): DecoderMode {
        val remembered = perFile.get(uri, KEY)?.let { runCatching { DecoderMode.valueOf(it) }.getOrNull() }
        return DecoderPolicy.pick(uri, settings.prefs.first(), remembered)
    }

    /** US-36.3. A no-op while the remember switch is off. */
    suspend fun rememberManualChoice(uri: String, mode: DecoderMode) {
        if (mode == DecoderMode.AUTO) perFile.remove(uri, KEY) else perFile.put(uri, KEY, mode.name)
    }

    companion object {
        const val KEY = "decoder"
    }
}

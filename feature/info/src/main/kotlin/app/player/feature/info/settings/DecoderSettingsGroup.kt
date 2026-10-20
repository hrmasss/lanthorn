package app.player.feature.info.settings

import android.content.Context
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.player.core.designsystem.component.SettingsChoice
import app.player.core.designsystem.component.SettingsHeader
import app.player.core.designsystem.component.SettingsNote
import app.player.core.designsystem.component.SettingsSwitch
import app.player.core.engine.DecoderMode
import app.player.core.engine.DecoderPrefs
import app.player.core.engine.DecoderSettings
import app.player.core.settingsapi.PreferencesJson
import app.player.core.settingsapi.SettingsEntry
import app.player.core.settingsapi.SettingsGroup
import app.player.feature.info.R
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The Decoder group (US-36): prefer software, and a decoder per file type. */
object DecoderSettingsGroup : SettingsGroup {
    override val id = "decoder"
    override val order = 30
    override val title = R.string.decoder_settings

    /** File types offered for a per-extension decoder (US-36.4). */
    val EXTENSIONS = listOf("mkv", "mp4", "avi", "webm", "mov", "ts", "m2ts", "flv", "wmv", "3gp", "mpg", "vob")

    override val entries = listOf(
        SettingsEntry("prefer_sw", R.string.decoder_prefer_sw, R.string.decoder_prefer_sw_summary),
        SettingsEntry("by_extension", R.string.decoder_by_extension, R.string.decoder_by_extension_summary),
    )

    @Composable
    override fun Content(highlight: String?) {
        val context = LocalContext.current
        val settings = remember { DecoderSettings(context) }
        val prefs by settings.prefs.collectAsStateWithLifecycle(DecoderPrefs())
        val scope = rememberCoroutineScope()
        val modes = listOf(
            DecoderMode.AUTO to stringResource(R.string.decoder_mode_auto),
            DecoderMode.HW to stringResource(R.string.decoder_mode_hw),
            DecoderMode.HW_PLUS to stringResource(R.string.decoder_mode_hw_plus),
            DecoderMode.SW to stringResource(R.string.decoder_mode_sw),
        )
        LazyColumn {
            item(key = "prefer_sw") {
                SettingsSwitch(
                    title = stringResource(R.string.decoder_prefer_sw),
                    summary = stringResource(R.string.decoder_prefer_sw_summary),
                    checked = prefs.preferSoftware,
                    onCheckedChange = { on -> scope.launch { settings.setPreferSoftware(on) } },
                    highlighted = highlight == "prefer_sw",
                )
            }
            item(key = "by_extension") {
                SettingsHeader(stringResource(R.string.decoder_by_extension))
                SettingsNote(stringResource(R.string.decoder_by_extension_summary))
            }
            items(EXTENSIONS, key = { "ext-$it" }) { ext ->
                SettingsChoice(
                    title = ".$ext",
                    options = modes,
                    selected = prefs.byExtension[ext] ?: DecoderMode.AUTO,
                    onSelect = { mode -> scope.launch { settings.setForExtension(ext, mode) } },
                    highlighted = highlight == "by_extension",
                )
            }
        }
    }

    override suspend fun reset(context: Context) = DecoderSettings(context).reset()

    override suspend fun export(context: Context): JSONObject = PreferencesJson.export(DecoderSettings(context).snapshot())

    override suspend fun import(context: Context, data: JSONObject) {
        DecoderSettings(context).restore { PreferencesJson.import(data, it) }
    }
}

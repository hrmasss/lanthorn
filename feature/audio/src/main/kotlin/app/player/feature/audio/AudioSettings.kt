package app.player.feature.audio

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.player.core.designsystem.component.SettingsChoice
import app.player.core.designsystem.component.SettingsHeader
import app.player.core.designsystem.component.SettingsNote
import app.player.core.designsystem.component.SettingsSwitch
import app.player.core.settingsapi.PreferencesJson
import app.player.core.settingsapi.SettingsEntry
import app.player.core.settingsapi.SettingsGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/** The Audio settings group (order 40): preferred language, default delay, Bluetooth delay, volume boost. */
object AudioSettings : SettingsGroup {
    override val id = "audio"
    override val order = 40
    override val title = R.string.audio_settings_title

    private const val LANGUAGE = "preferred_language"
    private const val DEFAULT_DELAY = "default_delay"
    private const val BLUETOOTH_DELAY = "bluetooth_delay"
    private const val VOLUME_BOOST = "volume_boost"
    private val KEYS = listOf("header", LANGUAGE, DEFAULT_DELAY, BLUETOOTH_DELAY, "note", VOLUME_BOOST)

    override val entries = listOf(
        SettingsEntry(LANGUAGE, R.string.audio_setting_language),
        SettingsEntry(DEFAULT_DELAY, R.string.audio_setting_default_delay, R.string.audio_setting_default_delay_summary),
        SettingsEntry(BLUETOOTH_DELAY, R.string.audio_setting_bluetooth_delay, R.string.audio_setting_bluetooth_delay_summary),
        SettingsEntry(VOLUME_BOOST, R.string.audio_setting_volume_boost, R.string.audio_setting_volume_boost_summary),
    )

    @Composable
    override fun Content(highlight: String?) {
        val context = LocalContext.current
        val store = remember { AudioPrefsStore(context) }
        val prefs by store.prefs.collectAsStateWithLifecycle(AudioPrefs())
        val scope = rememberCoroutineScope()
        val state = rememberLazyListState()
        LaunchedEffect(highlight) {
            val i = KEYS.indexOf(highlight)
            if (i >= 0) state.scrollToItem(i)
        }
        val none = stringResource(R.string.audio_setting_language_none)
        val languages = remember(none) {
            listOf("" to none) + LANGUAGES.map { it to Locale(it).getDisplayLanguage(Locale(it)).replaceFirstChar(Char::titlecase) }
        }
        LazyColumn(Modifier.fillMaxSize().testTag("settings-page"), state) {
            item("header") { SettingsHeader(stringResource(R.string.audio_settings_title)) }
            item(LANGUAGE) {
                SettingsChoice(
                    title = stringResource(R.string.audio_setting_language),
                    options = languages,
                    selected = prefs.preferredLanguage,
                    onSelect = { scope.launch { store.setLanguage(it) } },
                    modifier = Modifier.testTag("audio-setting-language"),
                    highlighted = highlight == LANGUAGE,
                )
            }
            item(DEFAULT_DELAY) {
                DelayRow(
                    stringResource(R.string.audio_setting_default_delay),
                    stringResource(R.string.audio_setting_default_delay_summary),
                    prefs.defaultDelayMs,
                    highlight == DEFAULT_DELAY,
                    "audio-setting-default-delay",
                ) { scope.launch { store.setDefaultDelay(it) } }
            }
            item(BLUETOOTH_DELAY) {
                DelayRow(
                    stringResource(R.string.audio_setting_bluetooth_delay),
                    stringResource(R.string.audio_setting_bluetooth_delay_summary),
                    prefs.bluetoothDelayMs,
                    highlight == BLUETOOTH_DELAY,
                    "audio-setting-bluetooth-delay",
                ) { scope.launch { store.setBluetoothDelay(it) } }
            }
            item("note") { SettingsNote(stringResource(R.string.audio_setting_delay_note)) }
            item(VOLUME_BOOST) {
                SettingsSwitch(
                    title = stringResource(R.string.audio_setting_volume_boost),
                    summary = stringResource(R.string.audio_setting_volume_boost_summary),
                    checked = prefs.volumeBoost,
                    onCheckedChange = { scope.launch { store.setVolumeBoost(it) } },
                    modifier = Modifier.testTag("audio-setting-volume-boost"),
                    highlighted = highlight == VOLUME_BOOST,
                )
            }
        }
    }

    override suspend fun reset(context: Context) {
        AudioPrefsStore(context).reset()
    }

    override suspend fun export(context: Context): JSONObject = PreferencesJson.export(AudioPrefsStore(context).store.data.first())

    override suspend fun import(context: Context, data: JSONObject) {
        AudioPrefsStore(context).store.edit { PreferencesJson.import(data, it) }
    }
}

/** A delay setting: the value, and steps of 10 and 100 ms either way. */
@Composable
private fun DelayRow(title: String, summary: String, ms: Long, highlighted: Boolean, tag: String, onChange: (Long) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(summary + "\n" + stringResource(R.string.audio_delay_value, ms / 1000.0), style = MaterialTheme.typography.bodyMedium)
        },
        colors = ListItemDefaults.colors(containerColor = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
        modifier = Modifier.heightIn(min = 56.dp).testTag(tag),
    )
    // Even widths across the row, same margin both sides; Reset puts it back to 0 in one tap.
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(-100L, -10L, 10L, 100L).forEach { step ->
            OutlinedButton(
                onClick = { onChange(ms + step) },
                modifier = Modifier.weight(1f).testTag("$tag$step"),
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) { Text("%+.2f".format(step / 1000.0)) }
        }
        TextButton(onClick = { onChange(0) }, enabled = ms != 0L, modifier = Modifier.testTag("$tag-reset")) {
            Text(stringResource(R.string.audio_reset))
        }
    }
}

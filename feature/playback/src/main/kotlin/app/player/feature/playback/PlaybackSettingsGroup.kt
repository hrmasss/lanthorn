package app.player.feature.playback

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.player.core.designsystem.component.SettingsChoice
import app.player.core.designsystem.component.SettingsHeader
import app.player.core.designsystem.component.SettingsSwitch
import app.player.core.settingsapi.PreferencesJson
import app.player.core.settingsapi.SettingsEntry
import app.player.core.settingsapi.SettingsGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The Playback group: resume (US-22) and opening links (US-13.2). Queue settings join with P1.12. */
object PlaybackSettingsGroup : SettingsGroup {
    override val id = "playback"
    override val order = 25
    override val title = R.string.settings_playback

    private const val RESUME = "resume"
    private const val FIRST_ONLY = "resume_first_only"
    private const val LINKS = "open_links"

    override val entries = listOf(
        SettingsEntry(RESUME, R.string.setting_resume),
        SettingsEntry(FIRST_ONLY, R.string.setting_resume_first_only, R.string.setting_resume_first_only_summary),
        SettingsEntry(LINKS, R.string.setting_open_links, R.string.setting_open_links_summary),
    )

    @Composable
    override fun Content(highlight: String?) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val prefs = remember { PlaybackPrefs(context) }
        val s by prefs.settings.collectAsStateWithLifecycle(PlaybackSettings())
        val order = listOf("h1", RESUME, FIRST_ONLY, "h2", LINKS)
        val list = rememberLazyListState()
        LaunchedEffect(highlight) {
            val i = order.indexOf(highlight)
            if (i >= 0) list.scrollToItem(i)
        }
        LazyColumn(Modifier.fillMaxSize().testTag("settings-page"), list) {
            item("h1") { SettingsHeader(stringResource(R.string.settings_section_resume)) }
            item(RESUME) {
                SettingsChoice(
                    stringResource(R.string.setting_resume),
                    listOf(
                        ResumeMode.Yes to stringResource(R.string.resume_yes),
                        ResumeMode.Ask to stringResource(R.string.resume_ask),
                        ResumeMode.No to stringResource(R.string.resume_no),
                    ),
                    s.resume,
                    { scope.launch { prefs.setResume(it) } },
                    highlighted = highlight == RESUME,
                )
            }
            item(FIRST_ONLY) {
                SettingsSwitch(
                    stringResource(R.string.setting_resume_first_only), s.resumeFirstOnly,
                    { scope.launch { prefs.setResumeFirstOnly(it) } },
                    summary = stringResource(R.string.setting_resume_first_only_summary),
                    enabled = s.resume != ResumeMode.No,
                    highlighted = highlight == FIRST_ONLY,
                )
            }
            item("h2") { SettingsHeader(stringResource(R.string.settings_section_open)) }
            item(LINKS) {
                SettingsSwitch(
                    stringResource(R.string.setting_open_links), s.openLinks,
                    {
                        scope.launch {
                            prefs.setOpenLinks(it)
                            OpenLinks.apply(context, it)
                        }
                    },
                    summary = stringResource(R.string.setting_open_links_summary),
                    highlighted = highlight == LINKS,
                )
            }
        }
    }

    override suspend fun reset(context: Context) {
        PlaybackPrefs(context).reset()
        OpenLinks.apply(context, PlaybackSettings().openLinks)
    }

    override suspend fun export(context: Context): JSONObject = PreferencesJson.export(PlaybackPrefs(context).store.data.first())

    override suspend fun import(context: Context, data: JSONObject) {
        val prefs = PlaybackPrefs(context)
        prefs.store.edit { PreferencesJson.import(data, it) }
        OpenLinks.apply(context, prefs.settings.first().openLinks)
    }
}

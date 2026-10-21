package app.player.feature.playback

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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

/** The Playback group: resume (US-22), lists (US-24, US-75), buttons (US-29), opening links (US-13.2), leaving, orientation and lock (US-58, US-56, US-28). */
object PlaybackSettingsGroup : SettingsGroup {
    override val id = "playback"
    override val order = 25
    override val title = R.string.settings_playback

    private const val RESUME = "resume"
    private const val FIRST_ONLY = "resume_first_only"
    private const val LINKS = "open_links"
    private const val BACK = "back_to_list"
    private const val SKIP = "skip_failed"
    private const val SMART = "smart_previous"
    private const val STEPS = "step_buttons"
    private const val PREV_NEXT = "show_previous_next"
    private const val LOCK = "lock_mode"
    private const val ORIENT = "orientation"
    private const val LEAVE = "on_leave"

    override val entries = listOf(
        SettingsEntry(RESUME, R.string.setting_resume),
        SettingsEntry(FIRST_ONLY, R.string.setting_resume_first_only, R.string.setting_resume_first_only_summary),
        SettingsEntry(BACK, R.string.setting_back_to_list, R.string.setting_back_to_list_summary),
        SettingsEntry(SKIP, R.string.setting_skip_failed, R.string.setting_skip_failed_summary),
        SettingsEntry(SMART, R.string.setting_smart_previous, R.string.setting_smart_previous_summary),
        SettingsEntry(STEPS, R.string.setting_step_buttons, R.string.setting_step_buttons_summary),
        SettingsEntry(PREV_NEXT, R.string.setting_show_previous_next),
        SettingsEntry(LINKS, R.string.setting_open_links, R.string.setting_open_links_summary),
        SettingsEntry(LEAVE, R.string.setting_on_leave),
        SettingsEntry(ORIENT, R.string.setting_orientation),
        SettingsEntry(LOCK, R.string.setting_lock_mode),
    )

    @Composable
    override fun Content(highlight: String?) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val prefs = remember { PlaybackPrefs(context) }
        val s by prefs.settings.collectAsStateWithLifecycle(PlaybackSettings())
        val order = listOf("h1", RESUME, FIRST_ONLY, "h3", BACK, SKIP, "h4", SMART, STEPS, PREV_NEXT, "h2", LINKS, "h5", LEAVE, ORIENT, LOCK)
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
            item("h3") { SettingsHeader(stringResource(R.string.settings_section_queue)) }
            switch(BACK, R.string.setting_back_to_list, R.string.setting_back_to_list_summary, s.backToList, highlight) { scope.launch { prefs.setBackToList(it) } }
            switch(SKIP, R.string.setting_skip_failed, R.string.setting_skip_failed_summary, s.skipFailed, highlight) { scope.launch { prefs.setSkipFailed(it) } }
            item("h4") { SettingsHeader(stringResource(R.string.settings_section_buttons)) }
            switch(SMART, R.string.setting_smart_previous, R.string.setting_smart_previous_summary, s.smartPrevious, highlight) { scope.launch { prefs.setSmartPrevious(it) } }
            switch(STEPS, R.string.setting_step_buttons, R.string.setting_step_buttons_summary, s.stepButtons, highlight) { scope.launch { prefs.setStepButtons(it) } }
            switch(PREV_NEXT, R.string.setting_show_previous_next, null, s.showPreviousNext, highlight) { scope.launch { prefs.setShowPreviousNext(it) } }
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
            item("h5") { SettingsHeader(stringResource(R.string.settings_section_screen)) }
            choice(LEAVE, R.string.setting_on_leave, listOf(OnLeave.Pip to R.string.on_leave_pip, OnLeave.Background to R.string.on_leave_background, OnLeave.Stop to R.string.on_leave_stop), s.onLeave, highlight) {
                scope.launch { prefs.setOnLeave(it) }
            }
            choice(
                ORIENT, R.string.setting_orientation,
                listOf(
                    OrientationMode.FollowVideo to R.string.orientation_follow,
                    OrientationMode.Landscape to R.string.orientation_landscape,
                    OrientationMode.ReverseLandscape to R.string.orientation_reverse,
                    OrientationMode.SensorLandscape to R.string.orientation_sensor_landscape,
                    OrientationMode.Sensor to R.string.orientation_sensor,
                    OrientationMode.System to R.string.orientation_system,
                ),
                s.orientation, highlight,
            ) { scope.launch { prefs.setOrientation(it) } }
            choice(LOCK, R.string.setting_lock_mode, listOf(LockMode.Lock to R.string.lock_mode_lock, LockMode.Kids to R.string.lock_mode_kids), s.lockMode, highlight) {
                scope.launch { prefs.setLockMode(it) }
            }
        }
    }

    private fun <T> LazyListScope.choice(key: String, @StringRes title: Int, options: List<Pair<T, Int>>, selected: T, highlight: String?, onSelect: (T) -> Unit) {
        item(key) {
            SettingsChoice(
                stringResource(title),
                options.map { (v, label) -> v to stringResource(label) },
                selected,
                onSelect,
                highlighted = highlight == key,
            )
        }
    }

    private fun LazyListScope.switch(key: String, @StringRes title: Int, @StringRes summary: Int?, checked: Boolean, highlight: String?, onChange: (Boolean) -> Unit) {
        item(key) {
            SettingsSwitch(
                stringResource(title), checked, onChange,
                summary = summary?.let { stringResource(it) },
                highlighted = highlight == key,
            )
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

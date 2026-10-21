package app.player.feature.library.settings

import android.content.Context
import app.player.feature.library.rememberMainScope
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.player.core.data.history.HistoryPrefs
import app.player.core.data.history.ResumePoints
import app.player.core.designsystem.component.ConfirmDialog
import app.player.core.designsystem.component.SettingsAction
import app.player.core.designsystem.component.SettingsChoice
import app.player.core.designsystem.component.SettingsHeader
import app.player.core.designsystem.component.SettingsNote
import app.player.core.designsystem.component.SettingsSwitch
import app.player.core.designsystem.component.SettingsText
import app.player.core.designsystem.theme.ThemeMode
import app.player.core.settingsapi.PreferencesJson
import app.player.core.settingsapi.SettingsEntry
import app.player.core.settingsapi.SettingsGroup
import app.player.core.settingsapi.settingsGroups
import app.player.feature.library.LibraryPrefs
import app.player.feature.library.LibraryPrefsStore
import app.player.feature.library.LibraryViewModel
import app.player.feature.library.R
import app.player.feature.library.network.NetworkPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** A settings page whose rows are LazyColumn items keyed by entry key, so search can scroll to one. */
@Composable
internal fun SettingsPage(highlight: String?, order: List<String>, content: LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(highlight) {
        val i = order.indexOf(highlight)
        if (i >= 0) state.scrollToItem(i)
    }
    LazyColumn(Modifier.fillMaxSize().testTag("settings-page"), state) { content() }
}

/** The library group: list settings (US-02.4). New, finished and resume marks join it with P1.21. */
object LibrarySettings : SettingsGroup {
    override val id = "library"
    override val order = 10
    override val title = R.string.settings_library

    private const val CACHE = "cache_thumbnails"
    private const val NEW_DAYS = "new_days"
    private const val MARK_LAST = "mark_last_played"
    private const val RESUME = "resume_button"
    override val entries = listOf(
        SettingsEntry(CACHE, R.string.setting_cache_thumbnails, R.string.setting_cache_thumbnails_summary),
        SettingsEntry(NEW_DAYS, R.string.setting_new_days),
        SettingsEntry(MARK_LAST, R.string.setting_mark_last, R.string.setting_mark_last_summary),
        SettingsEntry(RESUME, R.string.setting_resume_button, R.string.setting_resume_button_summary),
    )

    @Composable
    override fun Content(highlight: String?) {
        val context = LocalContext.current
        val store = remember { LibraryPrefsStore(context) }
        val prefs by store.prefs.collectAsStateWithLifecycle(LibraryPrefs())
        val scope = rememberMainScope()
        SettingsPage(highlight, listOf("h", CACHE, NEW_DAYS, MARK_LAST, RESUME)) {
            item("h") { SettingsHeader(stringResource(R.string.settings_section_list)) }
            item(CACHE) {
                SettingsSwitch(
                    stringResource(R.string.setting_cache_thumbnails),
                    prefs.cacheThumbnails,
                    { scope.launch { store.setCacheThumbnails(it) } },
                    summary = stringResource(R.string.setting_cache_thumbnails_summary),
                    highlighted = highlight == CACHE,
                )
            }
            item(NEW_DAYS) {
                SettingsChoice(
                    stringResource(R.string.setting_new_days),
                    listOf(1, 3, 7, 14, 30).map { it to pluralStringResource(R.plurals.days, it, it) },
                    prefs.newDays,
                    { scope.launch { store.setNewDays(it) } },
                    highlighted = highlight == NEW_DAYS,
                )
            }
            item(MARK_LAST) {
                SettingsSwitch(
                    stringResource(R.string.setting_mark_last), prefs.markLastPlayed,
                    { scope.launch { store.setMarkLastPlayed(it) } },
                    summary = stringResource(R.string.setting_mark_last_summary),
                    highlighted = highlight == MARK_LAST,
                )
            }
            item(RESUME) {
                SettingsSwitch(
                    stringResource(R.string.setting_resume_button), prefs.resumeButton,
                    { scope.launch { store.setResumeButton(it) } },
                    summary = stringResource(R.string.setting_resume_button_summary),
                    highlighted = highlight == RESUME,
                )
            }
        }
    }

    override suspend fun reset(context: Context) {
        LibraryPrefsStore(context).reset()
    }

    override suspend fun export(context: Context): JSONObject =
        PreferencesJson.export(LibraryPrefsStore(context).store.data.first(), skip = setOf("recent_searches"))

    override suspend fun import(context: Context, data: JSONObject) {
        LibraryPrefsStore(context).store.edit { PreferencesJson.import(data, it, skip = setOf("recent_searches")) }
    }
}

/** The general group: theme (US-67), language (US-68), backup (US-66), history (US-73), reset (US-65.2). */
object GeneralSettings : SettingsGroup {
    override val id = "general"
    override val order = 60
    override val title = R.string.settings_general

    private const val THEME = "theme"
    private const val BLACK = "pure_black"
    private const val DYNAMIC = "dynamic_color"
    private const val LANGUAGE = "language"
    private const val EXPORT = "export"
    private const val IMPORT = "import"
    private const val PAUSE = "pause_history"
    private const val CLEAR = "clear_history"
    private const val THUMBS = "clear_thumbnails"
    private const val RESET = "reset"
    private const val AGENT = "user_agent"
    private const val URL_HISTORY = "keep_url_history"

    override val entries = listOf(
        SettingsEntry(THEME, R.string.setting_theme),
        SettingsEntry(BLACK, R.string.setting_pure_black, R.string.setting_pure_black_summary),
        SettingsEntry(DYNAMIC, R.string.setting_dynamic_color, R.string.setting_dynamic_color_summary),
        SettingsEntry(LANGUAGE, R.string.setting_language, R.string.setting_language_summary),
        SettingsEntry(EXPORT, R.string.setting_export, R.string.setting_export_summary),
        SettingsEntry(IMPORT, R.string.setting_import, R.string.setting_import_summary),
        SettingsEntry(PAUSE, R.string.setting_pause_history, R.string.setting_pause_history_summary),
        SettingsEntry(CLEAR, R.string.setting_clear_history, R.string.setting_clear_history_summary),
        SettingsEntry(THUMBS, R.string.setting_clear_thumbnails, R.string.setting_clear_thumbnails_summary),
        SettingsEntry(AGENT, R.string.setting_user_agent, R.string.setting_user_agent_summary),
        SettingsEntry(URL_HISTORY, R.string.setting_url_history, R.string.setting_url_history_summary),
        SettingsEntry(RESET, R.string.setting_reset, R.string.setting_reset_summary),
    )

    @Composable
    override fun Content(highlight: String?) {
        val context = LocalContext.current
        val resources = LocalResources.current
        val locale = LocalConfiguration.current.locales[0]
        val scope = rememberMainScope()
        val appearancePrefs = remember { AppearancePrefs(context) }
        val history = remember { HistoryPrefs(context) }
        val appearance by appearancePrefs.appearance.collectAsStateWithLifecycle(Appearance())
        val paused by history.paused.collectAsStateWithLifecycle(false)
        val network = remember { NetworkPrefs(context) }
        val userAgent by network.userAgent.collectAsStateWithLifecycle("")
        val keepUrls by network.keepHistory.collectAsStateWithLifecycle(true)
        var confirm by remember { mutableStateOf<String?>(null) }
        var imported by remember { mutableStateOf<JSONObject?>(null) }
        var report by remember { mutableStateOf<String?>(null) }

        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) scope.launch {
                Backup.write(context, uri)
                context.toast(R.string.backup_exported)
            }
        }
        val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch {
                imported = Backup.read(context, uri)
                if (imported == null) context.toast(R.string.backup_not_a_backup)
            }
        }

        val order = listOf("h1", THEME, BLACK, DYNAMIC, LANGUAGE, "h2", EXPORT, IMPORT, "h3", PAUSE, CLEAR, THUMBS, "h5", AGENT, URL_HISTORY, "h4", RESET)
        SettingsPage(highlight, order) {
            item("h1") { SettingsHeader(stringResource(R.string.settings_section_look)) }
            item(THEME) {
                SettingsChoice(
                    stringResource(R.string.setting_theme),
                    listOf(
                        ThemeMode.System to stringResource(R.string.theme_system),
                        ThemeMode.Light to stringResource(R.string.theme_light),
                        ThemeMode.Dark to stringResource(R.string.theme_dark),
                    ),
                    appearance.mode,
                    { scope.launch { appearancePrefs.setMode(it) } },
                    highlighted = highlight == THEME,
                )
            }
            item(BLACK) {
                SettingsSwitch(
                    stringResource(R.string.setting_pure_black), appearance.pureBlack,
                    { scope.launch { appearancePrefs.setPureBlack(it) } },
                    summary = stringResource(R.string.setting_pure_black_summary),
                    enabled = appearance.mode != ThemeMode.Light,
                    highlighted = highlight == BLACK,
                )
            }
            item(DYNAMIC) {
                SettingsSwitch(
                    stringResource(R.string.setting_dynamic_color), appearance.dynamicColor,
                    { scope.launch { appearancePrefs.setDynamicColor(it) } },
                    summary = stringResource(R.string.setting_dynamic_color_summary),
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                    highlighted = highlight == DYNAMIC,
                )
            }
            item(LANGUAGE) {
                SettingsAction(
                    stringResource(R.string.setting_language),
                    { context.openLanguageSettings() },
                    summary = locale.getDisplayName(locale),
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                    highlighted = highlight == LANGUAGE,
                )
            }
            item("h2") { SettingsHeader(stringResource(R.string.settings_section_backup)) }
            item(EXPORT) {
                SettingsAction(
                    stringResource(R.string.setting_export),
                    { export.launch("lanthorn-backup-${DateFormat.getDateInstance().format(Date()).replace(Regex("[^A-Za-z0-9]+"), "-")}.json") },
                    summary = stringResource(R.string.setting_export_summary),
                    highlighted = highlight == EXPORT,
                )
            }
            item(IMPORT) {
                SettingsAction(
                    stringResource(R.string.setting_import),
                    { import.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                    summary = stringResource(R.string.setting_import_summary),
                    highlighted = highlight == IMPORT,
                )
            }
            item("h3") { SettingsHeader(stringResource(R.string.settings_section_history)) }
            item(PAUSE) {
                SettingsSwitch(
                    stringResource(R.string.setting_pause_history), paused,
                    { scope.launch { history.setPaused(it) } },
                    summary = stringResource(R.string.setting_pause_history_summary),
                    highlighted = highlight == PAUSE,
                )
            }
            item(CLEAR) {
                SettingsAction(
                    stringResource(R.string.setting_clear_history), { confirm = CLEAR },
                    summary = stringResource(R.string.setting_clear_history_summary),
                    highlighted = highlight == CLEAR,
                )
            }
            item(THUMBS) {
                SettingsAction(
                    stringResource(R.string.setting_clear_thumbnails),
                    {
                        scope.launch {
                            val freed = withContext(Dispatchers.IO) { LibraryViewModel.loaders(context).first.clear() }
                            report = resources.getString(R.string.thumbnails_cleared, Formatter.formatShortFileSize(context, freed))
                        }
                    },
                    summary = stringResource(R.string.setting_clear_thumbnails_summary),
                    highlighted = highlight == THUMBS,
                )
            }
            item("h5") { SettingsHeader(stringResource(R.string.settings_section_network)) }
            item(AGENT) {
                SettingsText(
                    stringResource(R.string.setting_user_agent),
                    userAgent,
                    { scope.launch { network.setUserAgent(it) } },
                    placeholder = stringResource(R.string.setting_user_agent_default),
                    highlighted = highlight == AGENT,
                )
            }
            item(URL_HISTORY) {
                SettingsSwitch(
                    stringResource(R.string.setting_url_history), keepUrls,
                    { scope.launch { network.setKeepHistory(it) } },
                    summary = stringResource(R.string.setting_url_history_summary),
                    highlighted = highlight == URL_HISTORY,
                )
            }
            item("h4") { SettingsHeader(stringResource(R.string.settings_section_reset)) }
            item(RESET) {
                SettingsAction(
                    stringResource(R.string.setting_reset), { confirm = RESET },
                    summary = stringResource(R.string.setting_reset_summary),
                    highlighted = highlight == RESET,
                )
            }
            item("note") { SettingsNote(stringResource(R.string.settings_history_note)) }
        }

        when (confirm) {
            CLEAR -> ConfirmDialog(
                stringResource(R.string.setting_clear_history),
                stringResource(R.string.confirm_clear_history),
                stringResource(R.string.action_clear),
                onConfirm = { scope.launch { clearHistory(context); context.toast(R.string.history_cleared) } },
                onDismiss = { confirm = null },
            )
            RESET -> ConfirmDialog(
                stringResource(R.string.setting_reset),
                stringResource(R.string.confirm_reset),
                stringResource(R.string.action_reset),
                onConfirm = {
                    scope.launch {
                        context.settingsGroups.forEach { it.reset(context) }
                        context.toast(R.string.settings_reset_done)
                    }
                },
                onDismiss = { confirm = null },
            )
        }
        imported?.let { backup -> ImportDialog(backup, onDone = { imported = null; report = it }) }
        report?.let { text ->
            AlertDialog(
                onDismissRequest = { report = null },
                text = { Text(text, Modifier.testTag("settings-report")) },
                confirmButton = { TextButton(onClick = { report = null }) { Text(stringResource(android.R.string.ok)) } },
            )
        }
    }

    override suspend fun reset(context: Context) {
        // Everything in the General file but the URL history, which is history, not a setting.
        AppearancePrefs(context).store.edit { prefs ->
            prefs.asMap().keys.filter { it.name != "url_history" }.forEach { prefs.remove(it) }
        }
        HistoryPrefs(context).setPaused(false)
    }

    override suspend fun export(context: Context): JSONObject = JSONObject()
        .put("appearance", PreferencesJson.export(AppearancePrefs(context).store.data.first(), skip = setOf("url_history")))
        .put("pause_history", HistoryPrefs(context).isPaused())

    override suspend fun import(context: Context, data: JSONObject) {
        data.optJSONObject("appearance")?.let { a ->
            AppearancePrefs(context).store.edit { PreferencesJson.import(a, it, skip = setOf("url_history")) }
        }
        if (data.has("pause_history")) HistoryPrefs(context).setPaused(data.optBoolean("pause_history"))
    }
}

/** US-73.1: resume points (and with them last-played marks) and search history. */
internal suspend fun clearHistory(context: Context) {
    ResumePoints(context).clear()
    LibraryPrefsStore(context).clearSearches()
    NetworkPrefs(context).clearHistory()
}

/** US-66.2: pick settings, history or both, then report what came back. */
@Composable
private fun ImportDialog(backup: JSONObject, onDone: (report: String?) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberMainScope()
    val hasSettings = (backup.optJSONObject("settings")?.length() ?: 0) > 0
    val hasHistory = (backup.optJSONObject("history")?.length() ?: 0) > 0
    var settings by remember { mutableStateOf(hasSettings) }
    var history by remember { mutableStateOf(hasHistory) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(stringResource(R.string.setting_import)) },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.import_settings)) },
                    leadingContent = { Checkbox(settings, { settings = it }, enabled = hasSettings) },
                    modifier = Modifier.testTag("import-settings"),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.import_history)) },
                    leadingContent = { Checkbox(history, { history = it }, enabled = hasHistory) },
                    modifier = Modifier.testTag("import-history"),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = settings || history,
                onClick = {
                    scope.launch {
                        val r = Backup.restore(context, backup, settings, history)
                        val parts = buildList {
                            if (settings) add(resources.getQuantityString(R.plurals.restored_groups, r.groups, r.groups))
                            r.history.forEach { (label, n) -> add("${resources.getString(label)}: $n") }
                        }
                        onDone(resources.getString(R.string.import_report, parts.joinToString("\n")))
                    }
                },
            ) { Text(stringResource(R.string.action_restore)) }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Toasts on the main thread, wherever the coroutine that finished the action runs. */
private fun Context.toast(text: Int) = ContextCompat.getMainExecutor(this).execute {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}

/** US-68.1: the system's per-app language page (Android 13+). */
private fun Context.openLanguageSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    startActivity(
        Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

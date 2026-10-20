package app.player.core.settingsapi

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import org.json.JSONObject

/**
 * One group on the settings screen (US-65.1): library, player, decoder, audio, subtitle or general.
 * The owning feature implements it with the shared rows in `core/designsystem` and lists it in the
 * Application (docs/decisions/2026-10-20-settings-api.md). The settings screen in feature/library
 * hosts the list, the search and reset.
 */
interface SettingsGroup {
    /** Stable id. Also the key of this group's part in a backup file. */
    val id: String

    /** Position in the list: library 10, player 20, decoder 30, audio 40, subtitle 50, general 60. */
    val order: Int

    @get:StringRes
    val title: Int

    /** Every setting in the group, for search. */
    val entries: List<SettingsEntry>

    /**
     * The group's page. [highlight] is the [SettingsEntry.key] the viewer picked in search: scroll
     * to it and mark it (LazyColumn item keys equal to entry keys make that one line).
     */
    @Composable
    fun Content(highlight: String?)

    /** Restores every setting in this group to its default (US-65.2). Not history, not caches. */
    suspend fun reset(context: Context)

    /** This group's settings for a backup file (US-66.1). Null if it has none worth keeping. */
    suspend fun export(context: Context): JSONObject? = null

    /** Restores from what [export] wrote. Unknown or missing keys keep their current value. */
    suspend fun import(context: Context, data: JSONObject) {}
}

/** One searchable setting: its title and summary as the viewer reads them. */
class SettingsEntry(
    val key: String,
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int? = null,
)

/**
 * Data that is not a setting but belongs in a backup (US-66.1): resume points, per-file choices,
 * playlists. Imported as "history" (US-66.2).
 */
interface BackupPart {
    val id: String

    @get:StringRes
    val label: Int

    suspend fun export(context: Context): JSONObject

    /** Returns how many items were restored, for the report. */
    suspend fun import(context: Context, data: JSONObject): Int
}

/** Implemented by the Application in `app`, which lists every feature's groups and backup parts. */
interface SettingsHost {
    val settingsGroups: List<SettingsGroup>
    val backupParts: List<BackupPart> get() = emptyList()
}

val Context.settingsGroups: List<SettingsGroup>
    get() = (applicationContext as? SettingsHost)?.settingsGroups.orEmpty().sortedBy { it.order }

val Context.backupParts: List<BackupPart>
    get() = (applicationContext as? SettingsHost)?.backupParts.orEmpty()

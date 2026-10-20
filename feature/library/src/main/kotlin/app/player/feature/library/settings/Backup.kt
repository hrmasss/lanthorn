package app.player.feature.library.settings

import android.content.Context
import android.net.Uri
import app.player.core.data.PlaybackPosition
import app.player.core.data.history.ResumePoints
import app.player.core.settingsapi.BackupPart
import app.player.core.settingsapi.backupParts
import app.player.core.settingsapi.settingsGroups
import app.player.feature.library.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One backup file (US-66): every settings group's export under "settings", every [BackupPart]
 * (resume points, per-file choices, playlists) under "history".
 */
object Backup {
    private const val FORMAT = "lanthorn-backup"
    private const val VERSION = 1

    data class Report(val groups: Int, val history: Map<Int, Int>)

    suspend fun build(context: Context): JSONObject {
        val settings = JSONObject()
        for (group in context.settingsGroups) group.export(context)?.let { settings.put(group.id, it) }
        val history = JSONObject()
        for (part in context.backupParts) history.put(part.id, part.export(context))
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("settings", settings)
            .put("history", history)
    }

    suspend fun write(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val text = build(context).toString(2)
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
    }

    /** Null when the file is not a backup this app wrote. */
    suspend fun read(context: Context, uri: Uri): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            JSONObject(text).takeIf { it.optString("format") == FORMAT }
        }.getOrNull()
    }

    /** Applies the parts the viewer chose and says what came back (US-66.2). */
    suspend fun restore(context: Context, backup: JSONObject, settings: Boolean, history: Boolean): Report {
        var groups = 0
        if (settings) {
            val data = backup.optJSONObject("settings") ?: JSONObject()
            for (group in context.settingsGroups) {
                data.optJSONObject(group.id)?.let {
                    group.import(context, it)
                    groups++
                }
            }
        }
        val restored = LinkedHashMap<Int, Int>()
        if (history) {
            val data = backup.optJSONObject("history") ?: JSONObject()
            for (part in context.backupParts) {
                data.optJSONObject(part.id)?.let { restored[part.label] = part.import(context, it) }
            }
        }
        return Report(groups, restored)
    }
}

/** Resume points in backups. Last-played marks come from the same rows. */
object ResumePointsBackup : BackupPart {
    override val id = "resume_points"
    override val label = R.string.backup_resume_points

    override suspend fun export(context: Context): JSONObject {
        val items = JSONArray()
        for (p in ResumePoints(context).all()) {
            items.put(
                JSONObject()
                    .put("uri", p.uri)
                    .put("positionMs", p.positionMs)
                    .put("durationMs", p.durationMs)
                    .put("updatedAt", p.updatedAt),
            )
        }
        return JSONObject().put("items", items)
    }

    override suspend fun import(context: Context, data: JSONObject): Int {
        val items = data.optJSONArray("items") ?: return 0
        val positions = (0 until items.length()).mapNotNull { i ->
            items.optJSONObject(i)?.let {
                runCatching {
                    PlaybackPosition(it.getString("uri"), it.getLong("positionMs"), it.getLong("durationMs"), it.getLong("updatedAt"))
                }.getOrNull()
            }
        }
        ResumePoints(context).putAll(positions)
        return positions.size
    }
}

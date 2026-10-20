package app.player.feature.info.settings

import android.content.Context
import app.player.core.data.choice.PerFileChoice
import app.player.core.data.choice.PerFileChoices
import app.player.core.settingsapi.BackupPart
import app.player.feature.info.R
import org.json.JSONArray
import org.json.JSONObject

/** The per_file_choice table in a backup file (US-66.1): decoder, tracks, delays per file. */
object PerFileChoicesBackup : BackupPart {
    override val id = "per_file_choice"
    override val label = R.string.backup_per_file_choices

    override suspend fun export(context: Context): JSONObject {
        val rows = JSONArray()
        PerFileChoices(context).all().forEach {
            rows.put(JSONObject().put("uri", it.uri).put("key", it.key).put("value", it.value).put("updatedAt", it.updatedAt))
        }
        return JSONObject().put("rows", rows)
    }

    override suspend fun import(context: Context, data: JSONObject): Int {
        val rows = data.optJSONArray("rows") ?: return 0
        val choices = (0 until rows.length()).mapNotNull { i ->
            val r = rows.optJSONObject(i) ?: return@mapNotNull null
            val uri = r.optString("uri").ifEmpty { return@mapNotNull null }
            val key = r.optString("key").ifEmpty { return@mapNotNull null }
            PerFileChoice(uri, key, r.optString("value"), r.optLong("updatedAt", System.currentTimeMillis()))
        }
        return PerFileChoices(context).restore(choices)
    }
}

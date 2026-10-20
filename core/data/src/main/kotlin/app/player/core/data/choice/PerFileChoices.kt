package app.player.core.data.choice

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.player.core.data.PlayerDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * One choice the viewer made for one file: decoder, track, delay, external subtitle.
 * Shared by every feature, owned by engine (docs/specs/03_architecture.md, per-file choices).
 * [key] is namespaced by owner: `decoder`, `audio.track`, `audio.delay`, `sub.track`,
 * `sub.delay`, `sub.external`. [value] is a string the owner parses.
 */
@Entity(tableName = "per_file_choice", primaryKeys = ["uri", "key"], indices = [Index("updatedAt")])
data class PerFileChoice(
    val uri: String,
    val key: String,
    val value: String,
    val updatedAt: Long,
)

@Dao
interface PerFileChoiceDao {
    @Query("SELECT * FROM per_file_choice WHERE uri = :uri")
    suspend fun forUri(uri: String): List<PerFileChoice>

    @Query("SELECT value FROM per_file_choice WHERE uri = :uri AND `key` = :key")
    suspend fun value(uri: String, key: String): String?

    @Upsert
    suspend fun put(choice: PerFileChoice)

    @Query("DELETE FROM per_file_choice WHERE uri = :uri AND `key` = :key")
    suspend fun remove(uri: String, key: String)

    @Query("DELETE FROM per_file_choice WHERE uri = :uri")
    suspend fun forget(uri: String)

    /** Drops every file beyond the [keep] most recently updated ones. */
    @Query(
        """DELETE FROM per_file_choice WHERE uri NOT IN (
            SELECT uri FROM per_file_choice GROUP BY uri ORDER BY MAX(updatedAt) DESC LIMIT :keep)""",
    )
    suspend fun keepNewest(keep: Int)

    @Transaction
    suspend fun putAndPrune(choice: PerFileChoice, keep: Int) {
        put(choice)
        keepNewest(keep)
    }

    // Backup (US-66.1) and clear history.
    @Query("SELECT * FROM per_file_choice")
    suspend fun all(): List<PerFileChoice>

    @Upsert
    suspend fun putAll(choices: List<PerFileChoice>)

    @Query("DELETE FROM per_file_choice")
    suspend fun clear()
}

private val Context.rememberStore: DataStore<Preferences> by preferencesDataStore("remember_per_file")

/** The shared "remember per-file choices" switch. player-ui writes it; everyone reads it. Default on. */
class RememberPerFile(context: Context) {
    private val store = context.applicationContext.rememberStore

    val enabled: Flow<Boolean> = store.data.map { it[KEY] ?: true }

    suspend fun isEnabled(): Boolean = enabled.first()

    /** Turning it off keeps stored rows; clearing them is [PerFileChoices.clear]. */
    suspend fun set(on: Boolean) {
        store.edit { it[KEY] = on }
    }

    private companion object {
        val KEY = booleanPreferencesKey("remember_per_file")
    }
}

/** Reads and writes per-file choices. Writes do nothing while [RememberPerFile] is off. */
class PerFileChoices(context: Context) {
    private val dao = PlayerDatabase.get(context).perFileChoices()
    private val remember = RememberPerFile(context)

    suspend fun get(uri: String): Map<String, String> =
        if (remember.isEnabled()) dao.forUri(uri).associate { it.key to it.value } else emptyMap()

    suspend fun get(uri: String, key: String): String? = if (remember.isEnabled()) dao.value(uri, key) else null

    suspend fun put(uri: String, key: String, value: String) {
        if (!remember.isEnabled()) return
        dao.putAndPrune(PerFileChoice(uri, key, value, System.currentTimeMillis()), CAP)
    }

    suspend fun remove(uri: String, key: String) = dao.remove(uri, key)

    suspend fun forget(uri: String) = dao.forget(uri)

    suspend fun clear() = dao.clear()

    /** Every stored choice, for a backup file (US-66.1). */
    suspend fun all(): List<PerFileChoice> = dao.all()

    /** Restores choices from a backup, keeping the cap. Returns how many were written. */
    suspend fun restore(choices: List<PerFileChoice>): Int {
        dao.putAll(choices)
        dao.keepNewest(CAP)
        return choices.size
    }

    companion object {
        /** Files remembered at most; the least recently changed go first. */
        const val CAP = 2000
    }
}

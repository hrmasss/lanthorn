package app.player.core.data.playlist

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.player.core.data.PlayerDatabase
import kotlinx.coroutines.flow.Flow

/** A named list of videos in the viewer's order (US-12). Owned by library. */
@Entity(tableName = "playlist")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

/**
 * One video in a playlist. [uri] is what the player opens; a uri is in a playlist at most once
 * (US-12.1). [name] is kept so a missing file can still be named (US-12.4).
 */
@Entity(
    tableName = "playlist_entry",
    primaryKeys = ["playlistId", "uri"],
    foreignKeys = [ForeignKey(Playlist::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("playlistId", "position")],
)
data class PlaylistEntry(
    val playlistId: Long,
    val uri: String,
    val name: String,
    val position: Int,
)

/** A playlist with its size, for the playlists list. */
data class PlaylistSummary(val id: Long, val name: String, val count: Int)

/**
 * An entry joined with the library index. [present] is false for a library file that is gone or
 * on an unmounted volume; uris the library does not index (streams, shares) count as present.
 */
data class PlaylistItem(
    val uri: String,
    val name: String,
    val position: Int,
    val videoId: Long?,
    val durationMs: Long?,
    val sizeBytes: Long?,
    val present: Boolean,
)

/** Entries and how many of them were added and skipped as duplicates (US-12.1). */
data class AddResult(val added: Int, val skipped: Int)

private const val ITEMS = """SELECT e.uri, e.name, e.position, v.id AS videoId, v.durationMs, v.sizeBytes,
    CASE WHEN e.uri NOT LIKE 'content://media/%' THEN 1 WHEN v.id IS NULL THEN 0 ELSE v.available END AS present
    FROM playlist_entry e LEFT JOIN library_video v ON v.uri = e.uri
    WHERE e.playlistId = :id ORDER BY e.position"""

@Dao
interface PlaylistDao {
    @Query(
        """SELECT p.id, p.name, COUNT(e.uri) AS count FROM playlist p
            LEFT JOIN playlist_entry e ON e.playlistId = p.id
            GROUP BY p.id ORDER BY p.name COLLATE NOCASE, p.id""",
    )
    fun summaries(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlist WHERE id = :id")
    fun playlist(id: Long): Flow<Playlist?>

    @Query(ITEMS)
    fun items(id: Long): Flow<List<PlaylistItem>>

    @Query(ITEMS)
    suspend fun itemsNow(id: Long): List<PlaylistItem>

    @Insert
    suspend fun insert(playlist: Playlist): Long

    @Query("UPDATE playlist SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT uri FROM playlist_entry WHERE playlistId = :id")
    suspend fun uris(id: Long): List<String>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_entry WHERE playlistId = :id")
    suspend fun lastPosition(id: Long): Int

    @Upsert
    suspend fun putEntries(entries: List<PlaylistEntry>)

    @Query("DELETE FROM playlist_entry WHERE playlistId = :id AND uri = :uri")
    suspend fun removeEntry(id: Long, uri: String)

    @Query("UPDATE playlist_entry SET position = :position WHERE playlistId = :id AND uri = :uri")
    suspend fun setPosition(id: Long, uri: String, position: Int)

    /** Appends [videos] (uri to name) in order, skipping uris already there or repeated (US-12.1). */
    @Transaction
    suspend fun add(id: Long, videos: List<Pair<String, String>>): AddResult {
        val have = uris(id).toHashSet()
        val fresh = videos.filter { have.add(it.first) }
        val start = lastPosition(id) + 1
        putEntries(fresh.mapIndexed { i, (uri, name) -> PlaylistEntry(id, uri, name, start + i) })
        return AddResult(fresh.size, videos.size - fresh.size)
    }

    @Transaction
    suspend fun create(name: String, now: Long, videos: List<Pair<String, String>>): Pair<Long, AddResult> {
        val id = insert(Playlist(name = name, createdAt = now))
        return id to add(id, videos)
    }

    /** Stores [uris] as the playlist's order (US-12.2). Uris not in the playlist are ignored. */
    @Transaction
    suspend fun reorder(id: Long, uris: List<String>) {
        uris.forEachIndexed { i, uri -> setPosition(id, uri, i) }
    }

    // Backup (US-66.1) and clear.
    @Query("SELECT * FROM playlist")
    suspend fun allPlaylists(): List<Playlist>

    @Query("SELECT * FROM playlist_entry")
    suspend fun allEntries(): List<PlaylistEntry>

    @Upsert
    suspend fun putPlaylists(playlists: List<Playlist>)
}

/** The playlist store. Every call runs on Room's own executor. */
class Playlists(context: Context) {
    private val dao = PlayerDatabase.get(context).playlists()

    val summaries: Flow<List<PlaylistSummary>> get() = dao.summaries()

    fun playlist(id: Long): Flow<Playlist?> = dao.playlist(id)

    fun items(id: Long): Flow<List<PlaylistItem>> = dao.items(id)

    suspend fun itemsNow(id: Long): List<PlaylistItem> = dao.itemsNow(id)

    suspend fun create(name: String, videos: List<Pair<String, String>> = emptyList()) =
        dao.create(name.trim(), System.currentTimeMillis(), videos)

    suspend fun add(id: Long, videos: List<Pair<String, String>>) = dao.add(id, videos)

    suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim())

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun remove(id: Long, uri: String) = dao.removeEntry(id, uri)

    suspend fun reorder(id: Long, uris: List<String>) = dao.reorder(id, uris)

    /** Every playlist with its entries in order, for backup (US-66.1). */
    suspend fun all(): List<Pair<Playlist, List<PlaylistEntry>>> {
        val entries = dao.allEntries().groupBy { it.playlistId }
        return dao.allPlaylists().map { p -> p to entries[p.id].orEmpty().sortedBy { it.position } }
    }

    /** Puts [playlist] back under its id and appends the entries it lacks. */
    suspend fun restore(playlist: Playlist, videos: List<Pair<String, String>>) {
        dao.putPlaylists(listOf(playlist))
        dao.add(playlist.id, videos)
    }
}

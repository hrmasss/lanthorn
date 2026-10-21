package app.player.core.data.library

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One video in the library index, mirrored from MediaStore. The index is a cache: MediaStore is the
 * source of truth, and the index lets the library draw its first frame without querying it (NF-1).
 */
@Entity(tableName = "library_video", indices = [Index("folder"), Index("uri")])
data class LibraryVideo(
    /** MediaStore `_ID`. Stable while the file exists. */
    @PrimaryKey val id: Long,
    /** `content://` uri the player opens. */
    val uri: String,
    /** MediaStore volume name: `external_primary`, or an SD card's id. */
    val volume: String,
    /** Folder key: volume plus relative path, e.g. `external_primary/Movies/Trips/`. See [folderKey]. */
    val folder: String,
    /** Absolute path where the platform still reports it. Used to name things, never to open them. */
    val path: String?,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    /** Last modified, epoch seconds. */
    val modifiedSec: Long,
    val mimeType: String?,
    /** False while the file's volume is unmounted (US-80.3). The row stays so lists can show it greyed. */
    @ColumnInfo(defaultValue = "1") val available: Boolean = true,
)

/** What the library learns by opening a file, cached so it opens each file once. */
@Entity(tableName = "video_extra")
data class VideoExtra(
    @PrimaryKey val id: Long,
    /** The file's [LibraryVideo.modifiedSec] when probed. A newer file is probed again. */
    val modifiedSec: Long,
    /** Average frames per second, 0 when unknown. */
    val fps: Float,
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_video")
    fun observeAll(): Flow<List<LibraryVideo>>

    @Query("SELECT * FROM library_video")
    suspend fun all(): List<LibraryVideo>

    @Upsert
    suspend fun upsert(videos: List<LibraryVideo>)

    @Query("DELETE FROM library_video WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT * FROM video_extra WHERE id = :id")
    suspend fun extra(id: Long): VideoExtra?

    @Upsert
    suspend fun putExtra(extra: VideoExtra)

    /** Applies a scan result in one transaction, so observers see one change, not two. */
    @Transaction
    suspend fun apply(diff: IndexDiff) {
        diff.removed.chunked(500).forEach { delete(it) }
        if (diff.changed.isNotEmpty()) upsert(diff.changed)
    }
}

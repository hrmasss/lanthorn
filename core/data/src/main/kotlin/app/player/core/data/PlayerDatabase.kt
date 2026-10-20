package app.player.core.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import app.player.core.data.choice.PerFileChoice
import app.player.core.data.choice.PerFileChoiceDao
import app.player.core.data.library.LibraryDao
import app.player.core.data.library.LibraryVideo
import app.player.core.data.library.VideoExtra

/** Where the viewer left a file. Keyed by the uri string the player was opened with. */
@Entity(tableName = "playback_position")
data class PlaybackPosition(
    @PrimaryKey val uri: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

@Dao
interface PlaybackPositionDao {
    @Query("SELECT * FROM playback_position WHERE uri = :uri")
    suspend fun get(uri: String): PlaybackPosition?

    @Upsert
    suspend fun put(position: PlaybackPosition)

    /** Every resume point, for backup (US-66.1) and library progress marks. */
    @Query("SELECT * FROM playback_position")
    suspend fun all(): List<PlaybackPosition>

    @Upsert
    suspend fun putAll(positions: List<PlaybackPosition>)

    /** Clear history (US-73.1). */
    @Query("DELETE FROM playback_position")
    suspend fun clear()
}

@Database(
    entities = [PlaybackPosition::class, LibraryVideo::class, VideoExtra::class, PerFileChoice::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class PlayerDatabase : RoomDatabase() {
    abstract fun playbackPositions(): PlaybackPositionDao
    abstract fun library(): LibraryDao
    abstract fun perFileChoices(): PerFileChoiceDao

    companion object {
        @Volatile private var instance: PlayerDatabase? = null

        /** Opening is lazy: Room touches disk on the first query, never on this call. */
        fun get(context: Context): PlayerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, PlayerDatabase::class.java, "player.db")
                .build()
                .also { instance = it }
        }
    }
}

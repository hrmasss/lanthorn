package app.player.core.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

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
}

@Database(entities = [PlaybackPosition::class], version = 1, exportSchema = true)
abstract class PlayerDatabase : RoomDatabase() {
    abstract fun playbackPositions(): PlaybackPositionDao

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

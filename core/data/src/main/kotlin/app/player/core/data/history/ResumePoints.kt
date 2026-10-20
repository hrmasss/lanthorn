package app.player.core.data.history

import android.content.Context
import app.player.core.data.PlaybackPosition
import app.player.core.data.PlayerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resume points as features see them, without Room on their classpath. */
class ResumePoints(context: Context) {
    private val dao = PlayerDatabase.get(context).playbackPositions()

    suspend fun all(): List<PlaybackPosition> = withContext(Dispatchers.IO) { dao.all() }

    suspend fun putAll(positions: List<PlaybackPosition>) = withContext(Dispatchers.IO) { dao.putAll(positions) }

    suspend fun clear() = withContext(Dispatchers.IO) { dao.clear() }
}

package app.player.feature.library.network

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.player.core.engine.PlayerEngine
import androidx.compose.runtime.Composable
import app.player.core.playerapi.PanelHost
import app.player.core.playerapi.PlayerExtension
import app.player.feature.library.settings.generalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Network settings. Kept in the General settings file, so backup and reset cover them. */
class NetworkPrefs(context: Context) {
    private val store = context.applicationContext.generalStore

    /** Empty means mpv's own user agent. */
    val userAgent: Flow<String> = store.data.map { it[USER_AGENT].orEmpty() }

    suspend fun setUserAgent(value: String) = store.edit {
        if (value.isBlank()) it.remove(USER_AGENT) else it[USER_AGENT] = value.trim()
    }

    /** "Keep URL history" (US-61.3), default on. Off keeps none and clears what was kept. */
    val keepHistory: Flow<Boolean> = store.data.map { it[KEEP_HISTORY] ?: true }

    /** Played URLs, newest first (US-61.3). */
    val history: Flow<List<String>> = store.data.map { recent(it) }

    suspend fun setKeepHistory(keep: Boolean) = store.edit {
        it[KEEP_HISTORY] = keep
        if (!keep) it.remove(HISTORY)
    }

    /** Records a played URL unless history is off or paused (US-73.2). */
    suspend fun addToHistory(url: String, paused: Boolean) = store.edit {
        if (paused || it[KEEP_HISTORY] == false) return@edit
        it[HISTORY] = (listOf(url) + recent(it).filterNot { u -> u == url }).take(MAX_HISTORY).joinToString("\n")
    }

    suspend fun removeFromHistory(url: String) = store.edit {
        it[HISTORY] = (recent(it) - url).joinToString("\n")
    }

    suspend fun clearHistory() = store.edit { it.remove(HISTORY) }

    internal companion object {
        /** For writes that must outlive the screen that starts them (a dialog that closes on Play). */
        val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val USER_AGENT = stringPreferencesKey("user_agent")
        val KEEP_HISTORY = booleanPreferencesKey("keep_url_history")
        /** History, not a setting: backups put it under history, resets leave it. */
        val HISTORY = stringPreferencesKey("url_history")
        const val MAX_HISTORY = 50

        fun recent(p: Preferences) = p[HISTORY]?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
    }
}

/**
 * Network wiring in the player (P1.8): the custom user agent goes to mpv before the first file
 * opens, so every request of a stream carries it (US-61.5); the overlay shows buffering and failures.
 */
object NetworkExtension : PlayerExtension {
    override val id = "network"

    override fun onEngineCreated(context: Context, engine: PlayerEngine) {
        // Runs on the main thread just before the first open. The engine applies calls in order, so
        // the agent must be set now, not after an async read. One small file read, bounded.
        val agent = runBlocking { withTimeoutOrNull(300) { NetworkPrefs(context).userAgent.first() } }
        if (!agent.isNullOrBlank()) engine.set("user-agent", agent)
        // mpv waits 60 s for a silent server. 20 s is long enough for slow links and short
        // enough that the viewer gets a reason and a retry (US-61.4) before giving up on us.
        engine.set("network-timeout", NETWORK_TIMEOUT_S)
    }

    private const val NETWORK_TIMEOUT_S = 20L

    @Composable
    override fun Overlay(host: PanelHost) = NetworkOverlay(host.engine)
}

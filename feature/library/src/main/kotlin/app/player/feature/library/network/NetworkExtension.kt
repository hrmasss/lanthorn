package app.player.feature.library.network

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.player.core.engine.PlayerEngine
import androidx.compose.runtime.Composable
import app.player.core.playerapi.PanelHost
import app.player.core.playerapi.PlayerExtension
import app.player.feature.library.settings.generalStore
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

    internal companion object {
        val USER_AGENT = stringPreferencesKey("user_agent")
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

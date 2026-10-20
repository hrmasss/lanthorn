package app.player.feature.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.player.core.data.choice.DecoderChoices
import app.player.core.designsystem.theme.LanthornPlayerTheme
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PlayerSessions
import app.player.core.playerapi.playerExtensions
import app.player.feature.player.settings.PlayerSettings
import app.player.feature.player.tools.PlaybackTools
import kotlinx.coroutines.launch

/** Plays the uri it was started with. Also the target for "open with" from other apps. */
class PlayerActivity : ComponentActivity() {
    private lateinit var engine: PlayerEngine
    private val decoders by lazy { DecoderChoices(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val extensions = playerExtensions
        engine = PlayerSessions.acquire(this, this) { created ->
            PlaybackTools(created).applyDefaults()
            extensions.forEach { it.onEngineCreated(applicationContext, created) }
        }
        if (savedInstanceState == null) open(intent)
        setContent {
            LanthornPlayerTheme {
                PlayerScreen(engine, extensions = extensions, settings = PlayerSettings.get(this), onDecoderPicked = { mode ->
                    val uri = engine.state.value.uri ?: return@PlayerScreen
                    lifecycleScope.launch { decoders.rememberManualChoice(uri, mode) }
                })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open(intent)
    }

    /**
     * Resume and the queue decide where and what plays (feature/playback), with the decoder US-36.1
     * picks. Without playback, the uri as is with that decoder. The lookup overlaps the engine's
     * wait for the surface, so it does not delay the first frame.
     */
    private fun open(intent: Intent) {
        if (playerExtensions.firstNotNullOfOrNull { it.playback }?.open(engine, intent, ::finish) == true) return
        val uri = intent.data ?: return
        lifecycleScope.launch { engine.open(uri, decoder = decoders.choose(uri.toString())) }
    }

    override fun onStop() {
        super.onStop()
        // Background play hands the engine to the playback service instead (P1.7, P1.15).
        engine.pause()
    }

    override fun onDestroy() {
        PlayerSessions.release(this)
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context, uri: Uri): Intent =
            Intent(context, PlayerActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

package app.player.feature.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import app.player.core.data.choice.DecoderChoices
import app.player.core.designsystem.theme.LanthornPlayerTheme
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PlaybackService
import app.player.core.engine.PlayerSessions
import app.player.core.playerapi.PlayerIntents
import app.player.core.playerapi.playerExtensions
import app.player.feature.player.settings.PlayerSettings
import app.player.feature.player.tools.PlaybackTools
import app.player.feature.player.tools.Deinterlace
import app.player.feature.player.tools.VideoTools
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
            // US-38.1: deinterlace defaults to auto (mpv's own default is off).
            VideoTools(created, applicationContext).setDeinterlace(Deinterlace.Auto)
            extensions.forEach { it.onEngineCreated(applicationContext, created) }
        }
        PlaybackService.openPlayer = { ctx ->
            Intent(ctx, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // For automation and until P1.15's setting lands: --ez app.player.background true.
        if (intent.hasExtra(EXTRA_BACKGROUND)) PlayerSessions.keepPlayingInBackground = intent.getBooleanExtra(EXTRA_BACKGROUND, false)
        if (savedInstanceState == null) open(intent)
        playerExtensions.firstNotNullOfOrNull { it.playback }?.attach(this)
        // A stalled engine is replaced by a new one (PlayerSessions): follow it.
        lifecycleScope.launch { PlayerSessions.current.collect { live -> if (live != null) engine = live } }
        setContent {
            LanthornPlayerTheme {
                val live by PlayerSessions.current.collectAsState()
                val engine = live ?: engine
                PlayerScreen(engine, extensions = extensions, settings = PlayerSettings.get(this), onDecoderPicked = { mode ->
                    val uri = engine.state.value.uri ?: return@PlayerScreen
                    lifecycleScope.launch { decoders.rememberManualChoice(uri, mode) }
                })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_BACKGROUND)) PlayerSessions.keepPlayingInBackground = intent.getBooleanExtra(EXTRA_BACKGROUND, false)
        open(intent)
    }

    /**
     * Resume and the queue decide where and what plays (feature/playback), with the decoder US-36.1
     * picks. Without playback, the uri as is with that decoder. The lookup overlaps the engine's
     * wait for the surface, so it does not delay the first frame.
     */
    private fun open(intent: Intent) {
        // Back from background play with the same file: keep going, do not reload (US-59.3).
        val now = engine.state.value
        if (now.loaded && intent.data != null && now.uri == intent.data.toString()) return
        engine.launchDecoder = PlayerIntents.decoderOf(intent)
        if (playerExtensions.firstNotNullOfOrNull { it.playback }?.open(engine, intent, ::finish) == true) return
        val uri = intent.data ?: return
        lifecycleScope.launch { engine.open(uri, decoder = decoders.choose(uri.toString())) }
    }

    // Playback sees keys first: volume keys as brightness (US-77), keys while locked (US-28.3).
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        playerExtensions.firstNotNullOfOrNull { it.playback }?.onKey(event) == true || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        playerExtensions.firstNotNullOfOrNull { it.playback }?.onKey(event) == true || super.onKeyUp(keyCode, event)

    override fun onStart() {
        super.onStart()
        // On screen again: the activity holds the engine, the service can let go.
        PlaybackService.stop(this)
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        // Background play (US-59.1) hands the engine to the playback service; otherwise pause.
        if (PlayerSessions.keepPlayingInBackground && !engine.state.value.paused) PlaybackService.start(this) else engine.pause()
    }

    override fun onDestroy() {
        PlayerSessions.release(this)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BACKGROUND = "app.player.background"

        fun intent(context: Context, uri: Uri): Intent =
            Intent(context, PlayerActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

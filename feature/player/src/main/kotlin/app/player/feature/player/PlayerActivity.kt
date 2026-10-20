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
import app.player.core.designsystem.PlayerTheme
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PlayerSessions
import app.player.core.playerapi.playerExtensions

/** Plays the uri it was started with. Also the target for "open with" from other apps. */
class PlayerActivity : ComponentActivity() {
    private lateinit var engine: PlayerEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val extensions = playerExtensions
        engine = PlayerSessions.acquire(this, this) { created ->
            extensions.forEach { it.onEngineCreated(applicationContext, created) }
        }
        if (savedInstanceState == null) intent.data?.let { engine.open(it) }
        setContent {
            PlayerTheme(darkTheme = true) { PlayerScreen(engine, extensions = extensions) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { engine.open(it) }
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

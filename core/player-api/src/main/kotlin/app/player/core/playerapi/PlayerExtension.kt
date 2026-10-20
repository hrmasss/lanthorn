package app.player.core.playerapi

import android.content.Context
import androidx.compose.runtime.Composable
import app.player.core.engine.PlayerEngine

/**
 * What a feature adds to the player screen without depending on feature/player
 * (docs/decisions/2026-10-20-player-extensions.md). Example: subs-audio contributes the subtitle
 * and audio panels and their menu entries.
 */
interface PlayerExtension {
    /** Stable id, for logs and ordering ties. */
    val id: String

    /** Panels this feature can show over the video. */
    val panels: List<PlayerPanel> get() = emptyList()

    /** Entries in the player's menus. Each one opens one of this feature's panels. */
    val menuEntries: List<PlayerMenuEntry> get() = emptyList()

    /**
     * Runs once per engine, on the main thread, before the first file opens. Apply saved settings
     * here (subtitle style, audio delay defaults) through the engine's generic property API.
     */
    fun onEngineCreated(context: Context, engine: PlayerEngine) {}

    /** Resume, queue, lock and PiP. Only playback's extension sets it; the player uses the first one. */
    val playback: PlaybackControls? get() = null

    /**
     * Drawn over the video, under the controls and panels, full size, always present while the
     * player shows. For state the feature owns, such as network buffering and an error card.
     * Draw nothing when there is nothing to say: it must not block touches it does not use.
     */
    @Composable
    fun Overlay(host: PanelHost) {}
}

/** A sheet or side panel shown over the video. The player screen owns placement and animation. */
interface PlayerPanel {
    val id: String

    @Composable
    fun Content(host: PanelHost)
}

/** What a panel gets from the player screen. */
interface PanelHost {
    val engine: PlayerEngine

    /** Close this panel. */
    fun close()

    /** Open another panel by id, for example a style panel from a track panel. */
    fun open(panelId: String)
}

enum class MenuGroup { Video, Audio, Subtitle, Tools }

/** One menu row. [label] is composable so it can use string resources. */
class PlayerMenuEntry(
    val id: String,
    val group: MenuGroup,
    val order: Int,
    val opensPanel: String,
    val label: @Composable () -> String,
)

/** Implemented by the Application in `app`, which lists every feature's extension. */
interface PlayerExtensionHost {
    val playerExtensions: List<PlayerExtension>
}

/** The extensions wired into this app, or none if the Application does not host any. */
val Context.playerExtensions: List<PlayerExtension>
    get() = (applicationContext as? PlayerExtensionHost)?.playerExtensions.orEmpty()

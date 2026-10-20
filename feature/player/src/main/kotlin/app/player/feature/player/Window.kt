package app.player.feature.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.Settings
import android.text.format.DateFormat
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.player.feature.player.overlay.StatusUi
import kotlinx.coroutines.delay
import java.util.Date

/** System bars follow the controls: hidden with them, back with them (US-16.5). */
@Composable
internal fun SystemBarsEffect(activity: Activity?, visible: Boolean) {
    LaunchedEffect(activity, visible) {
        val window = activity?.window ?: return@LaunchedEffect
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (visible) bars.show(WindowInsetsCompat.Type.systemBars()) else bars.hide(WindowInsetsCompat.Type.systemBars())
    }
}

/** Screen stays on while playing, and while paused if the viewer asked (US-60.1). */
@Composable
internal fun KeepScreenOnEffect(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/** True while TalkBack (touch exploration) is on, following changes live (US-69.2). */
@Composable
internal fun rememberTalkBackOn(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var on by remember { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

/**
 * Battery and clock while [active], refreshed on each minute boundary (US-60.3, US-60.4). Battery
 * is read from BatteryManager, no receiver; nothing runs while the status is not shown.
 */
@Composable
internal fun rememberStatus(active: Boolean): StatusUi? {
    val context = LocalContext.current
    var status by remember { mutableStateOf<StatusUi?>(null) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        val battery = context.getSystemService(BatteryManager::class.java)
        val format = DateFormat.getTimeFormat(context)
        while (true) {
            val now = System.currentTimeMillis()
            status = StatusUi(
                batteryPercent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 0,
                clock = format.format(Date(now)),
            )
            delay(60_000 - now % 60_000)
        }
    }
    return if (active) status else null
}

/**
 * Brightness of the player window only; the system setting is never written (US-18.4). Starts
 * from the system level the first time it moves.
 */
internal class WindowBrightness(private val activity: Activity) {
    /** 0..1. */
    val level: Float
        get() {
            val own = activity.window.attributes.screenBrightness
            if (own >= 0f) return own
            val system = runCatching {
                Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            }.getOrDefault(128)
            return system / 255f
        }

    fun set(value: Float) {
        val attrs = activity.window.attributes
        attrs.screenBrightness = value.coerceIn(0f, 1f)
        activity.window.attributes = attrs
    }

    /** Back to following the system. */
    fun reset() {
        val attrs = activity.window.attributes
        attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = attrs
    }
}

/** Media volume steps, with the system's own volume UI so TalkBack announces it. */
internal fun stepVolume(context: Context, up: Boolean) {
    val audio = context.getSystemService(AudioManager::class.java) ?: return
    audio.adjustStreamVolume(
        AudioManager.STREAM_MUSIC,
        if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
        AudioManager.FLAG_SHOW_UI,
    )
}

package app.player.feature.playback

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * "Open media links" (US-13.2) turns the http/https alias in this module's manifest on or off, so
 * the player is offered for media links only while the setting is on.
 */
object OpenLinks {
    private const val ALIAS = "app.player.feature.playback.OpenLink"

    fun apply(context: Context, on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val pm = context.packageManager
        val name = ComponentName(context.packageName, ALIAS)
        if (pm.getComponentEnabledSetting(name) != state) pm.setComponentEnabledSetting(name, state, PackageManager.DONT_KILL_APP)
    }
}

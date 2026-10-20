package app.player.feature.library

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** How much of the viewer's videos the app may read. */
enum class MediaAccess { Full, Partial, None }

/** The permissions one request asks for. On Android 14+ the system prompt offers "select videos" as well. */
@SuppressLint("InlinedApi") // Plain strings, chosen by sdk.
fun mediaPermissions(sdk: Int = Build.VERSION.SDK_INT): Array<String> = when {
    sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
        arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    sdk >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** Reads the grant state. A permission check is an in-memory lookup, safe on the main thread. */
fun Context.mediaAccess(): MediaAccess = mediaAccess(Build.VERSION.SDK_INT) { perm ->
    ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
}

internal fun mediaAccess(sdk: Int, granted: (String) -> Boolean): MediaAccess {
    val full = mediaPermissions(sdk).first()
    return when {
        granted(full) -> MediaAccess.Full
        sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.Partial
        else -> MediaAccess.None
    }
}

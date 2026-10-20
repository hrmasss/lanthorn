package app.player.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode { System, Light, Dark }

/**
 * App theme for library, settings and sheets. The player overlay does not use it for colour;
 * see [LanthornPlayerTheme].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LanthornTheme(
    mode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = true,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val dynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var scheme = when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> FallbackLight
    }
    if (dark && pureBlack) scheme = scheme.pureBlack()
    SystemBarIcons(darkContentBehind = dark)
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        typography = AppTypography,
        content = content,
    )
}

/**
 * Player overlay theme: always the dark scheme, standard (no overshoot) motion.
 * Fixed overlay colours live in [PlayerColors].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LanthornPlayerTheme(dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        FallbackDark
    }
    SystemBarIcons(darkContentBehind = true)
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.standard(),
        typography = AppTypography,
        content = content,
    )
}

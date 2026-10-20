package app.player.feature.library.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.player.core.designsystem.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** US-67.1 defaults: follow the system, dynamic colour on, pure black off. */
data class Appearance(
    val mode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val pureBlack: Boolean = false,
)

internal val Context.generalStore: DataStore<Preferences> by preferencesDataStore("general")

class AppearancePrefs(context: Context) {
    internal val store = context.applicationContext.generalStore

    val appearance: Flow<Appearance> = store.data.map {
        Appearance(
            mode = ThemeMode.entries.firstOrNull { m -> m.name == it[MODE] } ?: ThemeMode.System,
            dynamicColor = it[DYNAMIC] ?: true,
            pureBlack = it[PURE_BLACK] ?: false,
        )
    }

    suspend fun setMode(mode: ThemeMode) = store.edit { it[MODE] = mode.name }
    suspend fun setDynamicColor(on: Boolean) = store.edit { it[DYNAMIC] = on }
    suspend fun setPureBlack(on: Boolean) = store.edit { it[PURE_BLACK] = on }

    internal companion object {
        val MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val PURE_BLACK = booleanPreferencesKey("pure_black")
    }
}

/**
 * The viewer's theme choice for the app's screens, or null on the first frames before it is read.
 * The app draws nothing themed until then, so a dark choice never flashes light.
 */
@Composable
fun rememberAppearance(): Appearance? {
    val context = LocalContext.current
    val prefs = remember { AppearancePrefs(context) }
    val appearance by prefs.appearance.collectAsStateWithLifecycle(initialValue = null)
    return appearance
}

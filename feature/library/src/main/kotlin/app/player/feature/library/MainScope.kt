package app.player.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * A screen's scope whose store calls resume on the main thread, whatever dispatcher the
 * composition runs on (under the test clock it is not the main one, and a resumed call that
 * touches state then applies the composition off the main thread).
 */
@Composable
internal fun rememberMainScope() = rememberCoroutineScope { Dispatchers.Main.immediate }

package app.player

import android.app.Application
import app.player.core.playerapi.PlayerExtension
import app.player.core.playerapi.PlayerExtensionHost
import app.player.feature.info.InfoExtension

class PlayerApplication : Application(), PlayerExtensionHost {
    /** Every feature's player extension. Feature owners add theirs here. */
    override val playerExtensions: List<PlayerExtension> = listOf(
        InfoExtension,
    )
}

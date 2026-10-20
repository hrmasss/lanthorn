package app.player

import android.app.Application
import app.player.core.playerapi.PlayerExtension
import app.player.core.playerapi.PlayerExtensionHost
import app.player.core.settingsapi.BackupPart
import app.player.core.settingsapi.SettingsGroup
import app.player.core.settingsapi.SettingsHost
import app.player.feature.library.network.NetworkExtension
import app.player.feature.library.settings.GeneralSettings
import app.player.feature.library.settings.LibrarySettings
import app.player.feature.library.settings.ResumePointsBackup
import app.player.feature.audio.AudioExtension
import app.player.feature.subtitles.SubtitlesExtension

class PlayerApplication : Application(), PlayerExtensionHost, SettingsHost {
    /** Every feature's player extension. Feature owners add theirs here. */
    override val playerExtensions: List<PlayerExtension> = listOf(
        StreamInfoExtension,
        NetworkExtension,
        SubtitlesExtension,
        AudioExtension,
    )

    /** Every feature's settings group (core/settings-api). Owners add theirs; order comes from each group. */
    override val settingsGroups: List<SettingsGroup> = listOf(
        LibrarySettings,
        GeneralSettings,
    )

    /** Everything besides settings that goes into a backup file. */
    override val backupParts: List<BackupPart> = listOf(
        ResumePointsBackup,
    )
}

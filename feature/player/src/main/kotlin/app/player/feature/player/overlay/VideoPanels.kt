package app.player.feature.player.overlay

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.player.feature.player.R
import app.player.feature.player.settings.RadioRow
import app.player.feature.player.settings.SectionHeader
import app.player.feature.player.settings.SliderRow
import app.player.feature.player.settings.SwitchRow
import app.player.feature.player.tools.Deinterlace
import app.player.feature.player.tools.FitMode
import app.player.feature.player.tools.PictureAdjust
import app.player.feature.player.tools.VideoState
import app.player.feature.player.tools.VideoTools
import java.util.Locale
import kotlin.math.roundToInt

/** US-53.1 fit modes, US-54.1 aspect ratios with a custom one. */
@Composable
internal fun FitAspectContent(video: VideoState) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp).testTag("fit-panel")) {
        SectionHeader(stringResource(R.string.player_fit))
        FitMode.entries.forEach { m ->
            RadioRow(stringResource(m.label), video.fit == m, { video.chooseFit(m) }, Modifier.testTag("fit-${m.name}"))
        }
        SectionHeader(stringResource(R.string.video_aspect))
        val presets = VideoTools.ASPECTS
        presets.forEach { (name, ratio) ->
            val title = if (ratio == null) stringResource(R.string.video_aspect_default) else name
            RadioRow(title, video.aspect == ratio, { video.setAspect(ratio) }, Modifier.testTag("aspect-$name"))
        }
        val isCustom = video.aspect != null && presets.none { it.second == video.aspect }
        var custom by remember { mutableFloatStateOf((video.aspect ?: 1.85).toFloat()) }
        RadioRow(
            stringResource(R.string.video_aspect_custom),
            isCustom,
            { video.setAspect(custom.toDouble()) },
            Modifier.testTag("aspect-custom"),
            summary = String.format(Locale.ROOT, "%.2f:1", custom),
        )
        SliderRow(
            stringResource(R.string.video_aspect_custom),
            String.format(Locale.ROOT, "%.2f:1", custom),
            custom,
            0.5f..3.5f,
            299,
            { v ->
                custom = (v * 100).roundToInt() / 100f
                video.setAspect(custom.toDouble())
            },
            modifier = Modifier.testTag("aspect-custom-slider"),
        )
    }
}

/** US-57: mirror, flip, night mode and the four picture controls with a reset. */
@Composable
internal fun PictureContent(video: VideoState) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp).testTag("picture-panel")) {
        SectionHeader(stringResource(R.string.video_picture))
        SwitchRow(stringResource(R.string.sc_mirror), video.mirror, video::setMirror, Modifier.testTag("pic-mirror"))
        SwitchRow(stringResource(R.string.sc_vflip), video.verticalFlip, video::setVerticalFlip, Modifier.testTag("pic-vflip"))
        SwitchRow(
            stringResource(R.string.sc_night),
            video.night,
            video::setNight,
            Modifier.testTag("pic-night"),
            summary = stringResource(R.string.video_night_summary),
        )
        val p = video.picture
        listOf<Triple<Int, Int, (PictureAdjust, Int) -> PictureAdjust>>(
            Triple(R.string.video_brightness, p.brightness) { a, v -> a.copy(brightness = v) },
            Triple(R.string.video_contrast, p.contrast) { a, v -> a.copy(contrast = v) },
            Triple(R.string.video_saturation, p.saturation) { a, v -> a.copy(saturation = v) },
            Triple(R.string.video_gamma, p.gamma) { a, v -> a.copy(gamma = v) },
        ).forEach { (title, value, set) ->
            SliderRow(
                stringResource(title),
                value.toString(),
                value.toFloat(),
                -100f..100f,
                199,
                { v -> video.setPicture(set(video.picture, v.roundToInt())) },
                modifier = Modifier.testTag("pic-$title"),
            )
        }
        OutlinedButton(
            onClick = { video.setPicture(PictureAdjust()) },
            enabled = p != PictureAdjust(),
            modifier = Modifier.padding(horizontal = 24.dp).testTag("pic-reset"),
        ) { Text(stringResource(R.string.video_reset)) }
    }
}

internal val FitMode.label get() = when (this) {
    FitMode.Fit -> R.string.fit_fit
    FitMode.Stretch -> R.string.fit_stretch
    FitMode.Crop -> R.string.fit_crop
    FitMode.Original -> R.string.fit_original
}

package app.player.feature.player.tools

import android.content.Context
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PropertyType
import java.io.File
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * mpv wiring for fit, aspect, zoom, pan, flips, night mode, picture adjust and deinterlace (P1.5),
 * through the engine's generic property API only. The buttons and menus come with P1.10 and P1.16.
 *
 * Mirror, flip and night mode are GLSL hooks on the video plane, not FFmpeg filters: they run on
 * the GPU for every decoder including zero-copy HW, need no libavfilter, and leave subtitles alone
 * because mpv draws those after the video hooks. Removing a hook gives back the untouched frame.
 *
 * Properties set here, and only here: `keepaspect`, `panscan`, `video-unscaled`,
 * `video-aspect-override`, `video-zoom`, `video-pan-x`, `video-pan-y`, `brightness`, `contrast`,
 * `saturation`, `gamma`, `deinterlace`, and the `lanthorn-*` entries of `glsl-shaders`.
 */
class VideoTools(private val engine: PlayerEngine, context: Context) {
    private val shaderDir = File(context.filesDir, "shaders")

    /** US-53.1: one fit mode. */
    fun setFit(mode: FitMode) {
        engine.set("video-unscaled", mode == FitMode.Original)
        engine.set("keepaspect", mode != FitMode.Stretch)
        engine.set("panscan", if (mode == FitMode.Crop) 1.0 else 0.0)
    }

    /** US-54.1: null restores the file's own ratio. */
    fun setAspect(ratio: Double?) =
        engine.set("video-aspect-override", ratio?.let { String.format(Locale.ROOT, "%.6f", it) } ?: "no")

    /** US-55.1: 0.25 to 4.0 (25% to 400%). mpv's zoom is a power of two. */
    fun setZoom(scale: Double) = engine.set("video-zoom", zoomToMpv(clampZoom(scale)))

    /** US-55.3: pan in fractions of the scaled video, clamped by the caller with [maxPan]. */
    fun setPan(x: Double, y: Double) {
        engine.set("video-pan-x", x)
        engine.set("video-pan-y", y)
    }

    /** US-57.1. */
    fun setMirror(on: Boolean) = shader(MIRROR, on)
    fun setVerticalFlip(on: Boolean) = shader(VFLIP, on)

    /** US-57.2: brightness -30%, blue a further -20%, video only. */
    fun setNightMode(on: Boolean) = shader(NIGHT, on)

    /** US-57.3: each -100..100, 0 is the untouched picture. */
    fun setPicture(adjust: PictureAdjust) {
        engine.set("brightness", adjust.brightness.coerceIn(-100, 100).toLong())
        engine.set("contrast", adjust.contrast.coerceIn(-100, 100).toLong())
        engine.set("saturation", adjust.saturation.coerceIn(-100, 100).toLong())
        engine.set("gamma", adjust.gamma.coerceIn(-100, 100).toLong())
    }

    /** US-38: auto deinterlaces only frames flagged interlaced. Needs HW+ or SW (CPU frames). */
    fun setDeinterlace(mode: Deinterlace) = engine.set(
        "deinterlace",
        when (mode) {
            Deinterlace.Auto -> "auto"
            Deinterlace.On -> "yes"
            Deinterlace.Off -> "no"
        },
    )

    val deinterlace get() = engine.observe("deinterlace", PropertyType.Text)

    /** US-56.4: rotation from the file's metadata, which mpv applies by itself (`video-rotate` stays 0). */
    val rotation get() = engine.observe("video-params/rotate", PropertyType.Int64)

    private fun shader(hook: Hook, on: Boolean) {
        val file = File(shaderDir, "lanthorn-${hook.name}.glsl")
        if (on && !file.exists()) {
            shaderDir.mkdirs()
            file.writeText(hook.source)
        }
        engine.command("change-list", "glsl-shaders", if (on) "append" else "remove", file.path)
    }

    private class Hook(val name: String, val source: String)

    companion object {
        const val MIN_ZOOM = 0.25
        const val MAX_ZOOM = 4.0

        fun clampZoom(scale: Double) = scale.coerceIn(MIN_ZOOM, MAX_ZOOM)

        /** mpv `video-zoom` is log2 of the scale: 0 = 100%, 1 = 200%, -2 = 25%. */
        fun zoomToMpv(scale: Double): Double = ln(scale) / ln(2.0)
        fun zoomFromMpv(value: Double): Double = 2.0.pow(value)

        /**
         * Largest pan that keeps the picture's edge at the screen's edge (US-55.3): half the
         * overhang, in fractions of the scaled size. Zero when the picture fits.
         */
        fun maxPan(scaledSize: Double, viewSize: Double): Double =
            if (scaledSize <= 0) 0.0 else max(0.0, (scaledSize - viewSize) / (2 * scaledSize))

        /** US-54.1 presets, null = the file's own. */
        val ASPECTS: List<Pair<String, Double?>> = listOf(
            "default" to null,
            "1:1" to 1.0,
            "4:3" to 4.0 / 3,
            "16:9" to 16.0 / 9,
            "16:10" to 16.0 / 10,
            "21:9" to 21.0 / 9,
            "2.35:1" to 2.35,
            "2.39:1" to 2.39,
            "5:4" to 5.0 / 4,
        )

        private val MIRROR = Hook(
            "mirror",
            """
            //!HOOK MAIN
            //!BIND HOOKED
            //!DESC lanthorn mirror
            vec4 hook() { return HOOKED_tex(vec2(1.0 - HOOKED_pos.x, HOOKED_pos.y)); }
            """.trimIndent(),
        )
        private val VFLIP = Hook(
            "vflip",
            """
            //!HOOK MAIN
            //!BIND HOOKED
            //!DESC lanthorn vertical flip
            vec4 hook() { return HOOKED_tex(vec2(HOOKED_pos.x, 1.0 - HOOKED_pos.y)); }
            """.trimIndent(),
        )
        private val NIGHT = Hook(
            "night",
            """
            //!HOOK MAIN
            //!BIND HOOKED
            //!DESC lanthorn night mode
            vec4 hook() {
                vec4 c = HOOKED_texOff(0);
                c.rgb *= vec3(0.70, 0.70, 0.56);
                return c;
            }
            """.trimIndent(),
        )
    }
}

/** US-53.1 order: fit, stretch, crop (fill), 100%. */
enum class FitMode {
    Fit, Stretch, Crop, Original;

    fun next(): FitMode = entries[(ordinal + 1) % entries.size]
}

data class PictureAdjust(val brightness: Int = 0, val contrast: Int = 0, val saturation: Int = 0, val gamma: Int = 0)

enum class Deinterlace { Auto, On, Off }

package app.player.feature.subtitles

import android.content.Context
import android.system.Os
import java.io.File

/**
 * libass finds fonts through fontconfig, which has no configuration on Android and so finds no
 * font at all: subtitles parse but draw nothing. We write a small fonts.conf that points at the
 * system font folders (Roboto plus the Noto families for Arabic, Hebrew, Devanagari, Thai, CJK)
 * and name it in FONTCONFIG_FILE before the first file opens. fontconfig builds its cache once,
 * on mpv's thread, the first time a subtitle loads.
 */
internal object SubtitleFonts {
    fun install(context: Context, extraDirs: List<String> = emptyList()) {
        val dir = File(context.filesDir, "fontconfig").apply { mkdirs() }
        val conf = File(dir, "fonts.conf")
        val text = config(SYSTEM_DIRS.filter { File(it).isDirectory } + extraDirs, File(context.cacheDir, "fontconfig").path)
        if (!conf.exists() || conf.readText() != text) conf.writeText(text)
        Os.setenv("FONTCONFIG_FILE", conf.path, true)
    }

    internal fun config(dirs: List<String>, cacheDir: String): String = buildString {
        appendLine("""<?xml version="1.0"?>""")
        appendLine("""<!DOCTYPE fontconfig SYSTEM "fonts.dtd">""")
        appendLine("<fontconfig>")
        dirs.forEach { appendLine("  <dir>${escape(it)}</dir>") }
        appendLine("  <cachedir>${escape(cacheDir)}</cachedir>")
        ALIASES.forEach { (family, prefer) ->
            appendLine("  <alias><family>$family</family><prefer>${prefer.joinToString("") { "<family>$it</family>" }}</prefer></alias>")
        }
        appendLine("</fontconfig>")
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private val SYSTEM_DIRS = listOf("/system/fonts", "/product/fonts")

    /** Generic names in ASS files and mpv's default `sub-font` map to what Android ships. */
    private val ALIASES = listOf(
        "sans-serif" to listOf("Roboto", "Noto Sans", "Noto Sans Arabic", "Noto Sans Hebrew", "Noto Sans Devanagari", "Noto Sans Thai", "Noto Sans CJK JP"),
        "serif" to listOf("Noto Serif", "Roboto"),
        "monospace" to listOf("Droid Sans Mono", "Cutive Mono", "Roboto"),
        "Arial" to listOf("Roboto"),
        "Helvetica" to listOf("Roboto"),
        "Times New Roman" to listOf("Noto Serif"),
    )
}

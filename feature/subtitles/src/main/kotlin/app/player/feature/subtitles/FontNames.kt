package app.player.feature.subtitles

import java.io.File
import java.io.RandomAccessFile

/**
 * Family names of font files, read from the OpenType `name` table (name id 1), so the font
 * picker shows what libass will match. Reads a few kilobytes per file. IO thread only.
 */
internal object FontNames {
    private val EXTENSIONS = setOf("ttf", "otf", "ttc")

    fun families(dirs: List<File>): List<String> = dirs
        .flatMap { it.listFiles()?.toList().orEmpty() }
        .filter { it.extension.lowercase() in EXTENSIONS }
        .mapNotNull { runCatching { family(it) }.getOrNull() }
        .distinct()
        .sorted()

    fun family(file: File): String? = RandomAccessFile(file, "r").use { f ->
        var base = 0L
        if (f.readTag() == "ttcf") {
            f.seek(12)
            base = f.readInt().toLong() and 0xFFFFFFFFL // first font in the collection
        }
        f.seek(base + 4)
        val tables = f.readUnsignedShort()
        f.seek(base + 12)
        repeat(tables) {
            val tag = f.readTag()
            f.readInt() // checksum
            val offset = f.readInt().toLong() and 0xFFFFFFFFL
            f.readInt() // length
            if (tag == "name") return readFamily(f, offset)
            f.seek(base + 12 + 16L * (it + 1))
        }
        null
    }

    private fun readFamily(f: RandomAccessFile, table: Long): String? {
        f.seek(table + 2)
        val count = f.readUnsignedShort()
        val strings = table + f.readUnsignedShort()
        var fallback: String? = null
        repeat(count) {
            val platform = f.readUnsignedShort()
            val encoding = f.readUnsignedShort()
            val language = f.readUnsignedShort()
            val nameId = f.readUnsignedShort()
            val length = f.readUnsignedShort()
            val offset = f.readUnsignedShort()
            if (nameId == 1) {
                val here = f.filePointer
                f.seek(strings + offset)
                val bytes = ByteArray(length).also(f::readFully)
                f.seek(here)
                val text = if (platform == 3 || platform == 0) String(bytes, Charsets.UTF_16BE) else String(bytes, Charsets.ISO_8859_1)
                // Windows, English (US) first; any family name otherwise.
                if (platform == 3 && encoding == 1 && language == 0x409) return text
                if (fallback == null) fallback = text
            }
        }
        return fallback
    }

    private fun RandomAccessFile.readTag(): String = ByteArray(4).also(::readFully).toString(Charsets.ISO_8859_1)
}

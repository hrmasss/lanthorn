package app.player.feature.subtitles

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PlayerSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The live subtitle style: one value for the process, applied to the engine on every change so
 * the viewer sees it on the current line at once (US-42.3), saved a moment later. Main thread.
 */
internal class SubtitleStyles(context: Context) {
    private val appContext = context.applicationContext
    private val store = SubtitleStyleStore(appContext)
    private val scope = MainScope()
    private val _style = MutableStateFlow(SubtitleStyle())
    val style: StateFlow<SubtitleStyle> = _style.asStateFlow()
    private var loaded = false

    /**
     * The navigation bar or gesture handle, as a percent of the screen height in landscape.
     * Subtitles keep at least this bottom margin. Read from the system dimension; 24 dp if absent.
     */
    @android.annotation.SuppressLint("DiscouragedApi", "InternalInsetResource")
    val navigationInsetPercent: Float = appContext.resources.let { r ->
        val id = r.getIdentifier("navigation_bar_height_landscape", "dimen", "android")
            .takeIf { it != 0 } ?: r.getIdentifier("navigation_bar_height", "dimen", "android")
        val px = if (id != 0) r.getDimensionPixelSize(id) else (24 * r.displayMetrics.density).toInt()
        val shortSide = minOf(r.displayMetrics.widthPixels, r.displayMetrics.heightPixels).coerceAtLeast(1)
        100f * px / shortSide
    }

    /** Folder that holds fonts the viewer imported (US-42.1). fontconfig scans it. */
    val userFontDir = File(appContext.filesDir, "fonts")

    init {
        @OptIn(FlowPreview::class)
        scope.launch { _style.drop(1).debounce(SAVE_DELAY_MS).collect { store.save(it) } }
    }

    /** Applies the style now, then the saved one once it has been read (first engine only). */
    fun attach(engine: PlayerEngine) {
        apply(engine, _style.value)
        if (loaded) return
        loaded = true
        scope.launch {
            val saved = store.load()
            if (saved != _style.value) {
                _style.value = saved
                PlayerSessions.current.value?.let { apply(it, saved) }
            }
        }
    }

    fun current(): SubtitleStyle = _style.value

    /** For screens that show the style before any player opened (settings). */
    suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val saved = store.load()
        if (saved != _style.value) _style.value = saved
    }

    fun update(block: (SubtitleStyle) -> SubtitleStyle) {
        val next = block(_style.value)
        if (next == _style.value) return
        _style.value = next
        PlayerSessions.current.value?.let { apply(it, next) }
    }

    fun reset() = update { SubtitleStyle() }

    /** Copies font files from a folder the viewer picked. They load when the next file opens. */
    suspend fun importFonts(tree: Uri): Int = withContext(Dispatchers.IO) {
        userFontDir.mkdirs()
        val resolver = appContext.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var copied = 0
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.substringAfterLast('.').lowercase() !in FONT_EXTENSIONS) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                resolver.openInputStream(uri)?.use { input -> File(userFontDir, name).outputStream().use { input.copyTo(it) } }
                copied++
            }
        }
        copied
    }

    private var systemFamilies: List<String>? = null

    /**
     * Families for the font picker: the generic ones, system fonts that can draw Latin text (no
     * clock digits, emoji or symbol sets), then the viewer's own. System fonts are read once.
     */
    suspend fun families(): List<String> = withContext(Dispatchers.IO) {
        val system = systemFamilies ?: textFamilies(listOf(File("/system/fonts"), File("/product/fonts"))).also { systemFamilies = it }
        (GENERIC + system + textFamilies(listOf(userFontDir))).distinct()
    }

    private fun textFamilies(dirs: List<File>): List<String> = dirs
        .flatMap { it.listFiles()?.toList().orEmpty() }
        .filter { it.extension.lowercase() in FONT_EXTENSIONS && drawsText(it) }
        .mapNotNull { runCatching { FontNames.family(it) }.getOrNull() }
        .filterNot { family -> NOT_TEXT.any { it in family } }
        .distinct()
        .sorted()

    /** A font for subtitles has the Latin letters; clock, emoji and symbol fonts do not. */
    private fun drawsText(file: File): Boolean = runCatching {
        val paint = android.graphics.Paint().apply { typeface = android.graphics.Typeface.createFromFile(file) }
        LATIN.all { paint.hasGlyph(it.toString()) }
    }.getOrDefault(false)

    private var appliedTo: PlayerEngine? = null
    private var applied: Map<String, String> = emptyMap()

    /** Sends only what changed since the last call for this engine: one slider move, one property. */
    private fun apply(engine: PlayerEngine, style: SubtitleStyle) {
        if (appliedTo !== engine) {
            appliedTo = engine
            applied = emptyMap()
        }
        val next = style.toMpv(navigationInsetPercent)
        changed(applied, next).forEach { (name, value) -> engine.set(name, value) }
        applied = next.toMap()
    }

    companion object {
        /** The properties of [next] whose value differs from [applied], in [next]'s order. */
        internal fun changed(applied: Map<String, String>, next: List<Pair<String, String>>) =
            next.filter { (name, value) -> applied[name] != value }

        private const val SAVE_DELAY_MS = 500L
        private val LATIN = ('a'..'z') + ('A'..'Z')

        /** Families that pass the glyph check but are not for reading: the lock-screen clock, emoji, symbols. */
        private val NOT_TEXT = listOf("Clock", "Emoji", "Symbols", "Music", "Math")
        private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")
        private val GENERIC = listOf(SubtitleStyle.DEFAULT_FONT, "serif", "monospace")
    }
}

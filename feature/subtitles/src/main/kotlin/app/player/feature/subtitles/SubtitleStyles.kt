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

    /** Families for the font picker: the generic ones, system fonts, then the viewer's own. */
    suspend fun families(): List<String> = withContext(Dispatchers.IO) {
        GENERIC + (FontNames.families(listOf(File("/system/fonts"), File("/product/fonts"), userFontDir)) - GENERIC.toSet())
    }

    private var appliedTo: PlayerEngine? = null
    private var applied: Map<String, String> = emptyMap()

    /** Sends only what changed since the last call for this engine: one slider move, one property. */
    private fun apply(engine: PlayerEngine, style: SubtitleStyle) {
        if (appliedTo !== engine) {
            appliedTo = engine
            applied = emptyMap()
        }
        val next = style.toMpv()
        changed(applied, next).forEach { (name, value) -> engine.set(name, value) }
        applied = next.toMap()
    }

    companion object {
        /** The properties of [next] whose value differs from [applied], in [next]'s order. */
        internal fun changed(applied: Map<String, String>, next: List<Pair<String, String>>) =
            next.filter { (name, value) -> applied[name] != value }

        private const val SAVE_DELAY_MS = 500L
        private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")
        private val GENERIC = listOf(SubtitleStyle.DEFAULT_FONT, "serif", "monospace")
    }
}

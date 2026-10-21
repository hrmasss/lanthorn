package app.player.feature.subtitles

import android.content.Context
import android.net.Uri
import app.player.core.data.choice.PerFileChoices
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PropertyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs once per opened file: adds the subtitle files that belong to it (US-39.1), then turns on
 * the remembered track (US-39.4), or the automatic choice (US-39.2), or none.
 */
internal class SubtitleAutoLoad(context: Context, private val files: SubtitleFiles) {
    private val appContext = context.applicationContext
    private val discovery = SubtitleDiscovery(appContext)
    private val prefs = SubtitlePrefsStore(appContext)
    private val choices = PerFileChoices(appContext)

    /** What the last scan saw, for the panel's "find subtitles in this folder" offer. */
    @Volatile var lastResult: SubtitleDiscovery.Result? = null
        private set

    private val _doneFor = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** The uri the last auto-load finished for: anything that picks tracks itself waits for it. */
    val doneFor: kotlinx.coroutines.flow.StateFlow<String?> = _doneFor

    /** A new file started loading: the last result no longer applies. */
    fun clear() {
        _doneFor.value = null
    }

    fun folderUri(place: SubtitleDiscovery.Place): Uri? = discovery.folderUri(place)

    suspend fun onFileLoaded(engine: PlayerEngine, uri: String) {
        val p = prefs.current()
        val result = discovery.find(Uri.parse(uri), p.subtitleFolder.takeIf { it.isNotEmpty() }?.let(Uri::parse))
        lastResult = result
        val controller = SubtitleController(engine.asMpv())
        val before = engine.get("track-list/count", PropertyType.Int64) ?: 0
        for (found in result.found) {
            val prepared = runCatching { files.prepare(found.uri, companion = found.companion) }.getOrNull() ?: continue
            val lang = result.place?.let { SubtitleMatch.languageOf(it.videoName, found.name) }
            controller.addFile(prepared.path, found.name, lang, select = false)
        }
        // Wait until mpv lists them before choosing.
        if (result.found.isNotEmpty()) {
            withTimeoutOrNull(2000) {
                while ((engine.get("track-list/count", PropertyType.Int64) ?: 0) < before + result.found.size) delay(20)
            }
        }
        val tracks = parseTracks(engine.get("track-list", PropertyType.Text))
        val key = choices.get(uri, TRACK_KEY)
        val remembered = key?.let { k -> tracks.firstOrNull { it.type == "sub" && trackKey(it) == k } }
        val pick = when {
            key == NONE -> null
            remembered != null -> remembered
            p.showAutomatically -> SubtitleMatch.autoTrack(tracks, languageForms(p.preferredLanguage))
            else -> null
        }
        controller.select(SubSelection(pick?.id))
        _doneFor.value = uri
    }

    /** US-39.4: the viewer turned [track] on (or none) for [uri]. No-op while "remember choices" is off. */
    suspend fun remember(uri: String, track: Track?) {
        if (track == null) choices.put(uri, TRACK_KEY, NONE) else choices.put(uri, TRACK_KEY, trackKey(track))
    }

    companion object {
        const val TRACK_KEY = "sub.track"
        private const val NONE = "none"

        /** External tracks by file name (their ids change with load order), embedded ones by id. */
        fun trackKey(track: Track): String =
            if (track.external) "ext:" + (track.title ?: track.externalFilename.orEmpty()) else "emb:${track.id}"
    }
}

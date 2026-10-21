package app.player.feature.library

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.data.library.LibraryDao
import app.player.core.data.library.LibraryIndex
import app.player.core.data.library.LibraryVideo
import app.player.core.data.library.VideoExtra
import app.player.core.data.library.VideoSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertTrue

/** An index in memory, so library screens can be tested with any number of videos. */
internal class MemoryDao : LibraryDao {
    val rows = MutableStateFlow<Map<Long, LibraryVideo>>(emptyMap())
    override fun observeAll() = rows.map { it.values.toList() }
    override suspend fun all() = rows.value.values.toList()
    override suspend fun upsert(videos: List<LibraryVideo>) { rows.value = rows.value + videos.associateBy { it.id } }
    override suspend fun delete(ids: List<Long>) { rows.value = rows.value - ids.toSet() }
    override suspend fun extra(id: Long): VideoExtra? = null
    override suspend fun putExtra(extra: VideoExtra) = Unit
}

internal fun testVideo(id: Long, folder: String = "external_primary/Movies/", name: String = "Clip $id.mp4") = LibraryVideo(
    id, "content://media/external_primary/video/media/999999$id", "external_primary", folder, null, name,
    1_000_000, 60_000, 1920, 1080, 1_700_000_000 + id, "video/mp4",
)

/** A view model over [dao] whose scans return [scan]'s list, ready with full access. */
internal fun testViewModel(
    dao: MemoryDao,
    prefs: LibraryPrefs = LibraryPrefs(),
    store: LibraryPrefsStore? = null,
    scan: () -> List<LibraryVideo>,
): Pair<LibraryViewModel, LibraryIndex> {
    val index = LibraryIndex(dao, object : VideoSource {
        override fun scan() = scan()
        override fun mountedVolumes() = setOf("external_primary")
    })
    val vm = LibraryViewModel(
        index, store, { listOf(Volume("external_primary", "Internal shared storage")) },
        prefs = store?.prefs ?: flowOf(prefs),
    )
    return vm to index
}

internal fun testLoaders(context: Context, index: LibraryIndex) = ThumbnailLoader(context) to VideoProbe(context, index)

/** A timing bound; hosted CI passes `-e timing lenient`, where it is logged and never fails (emulator numbers claim nothing). */
internal fun assertWithin(what: String, tookMs: Long, limitMs: Long) {
    val lenient = InstrumentationRegistry.getArguments().getString("timing") == "lenient"
    Log.i("Timing", "$what: $tookMs ms (limit $limitMs)" + if (lenient && tookMs > limitMs) ", over, lenient run" else "")
    if (!lenient) assertTrue("$what took $tookMs ms (limit $limitMs)", tookMs <= limitMs)
}

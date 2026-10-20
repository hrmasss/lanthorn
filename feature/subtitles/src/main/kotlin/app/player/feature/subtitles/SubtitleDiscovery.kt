package app.player.feature.subtitles

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Finds subtitle files that belong to a video (US-39.1). Under scoped storage the app cannot list
 * a folder it only has video access to, so it lists through folder grants the viewer gave once
 * (orchestrator ruling, option a): a grant covers its folder and everything below it. Files the
 * app can read directly (its own storage, `file://` paths) are listed as they are.
 */
internal class SubtitleDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver get() = appContext.contentResolver

    /** One subtitle file beside the video: where to read it, and its name. */
    data class Found(val uri: Uri, val name: String)

    /** Where a video sits: its file name, and its folder as a storage document id ("primary:Movies"). */
    data class Place(val videoName: String, val folderDocId: String?, val folderFile: File?)

    class Result(val found: List<Found>, val place: Place?, val folderGranted: Boolean)

    suspend fun find(video: Uri, subtitleFolder: Uri?): Result = withContext(Dispatchers.IO) {
        val place = runCatching { placeOf(video) }.getOrNull() ?: return@withContext Result(emptyList(), null, false)
        val names = mutableMapOf<String, Uri>()
        var granted = false
        place.folderFile?.takeIf { it.canRead() }?.listFiles()?.forEach { names[it.name] = Uri.fromFile(it) }
        if (place.folderFile?.canRead() == true) granted = true
        place.folderDocId?.let { folder ->
            grantedTrees().firstOrNull { covers(DocumentsContract.getTreeDocumentId(it), folder) }?.let { tree ->
                granted = true
                children(tree, folder).forEach { (name, uri) -> names.putIfAbsent(name, uri) }
            }
        }
        subtitleFolder?.let { tree ->
            runCatching { children(tree, DocumentsContract.getTreeDocumentId(tree)) }.getOrDefault(emptyList())
                .forEach { (name, uri) -> names.putIfAbsent(name, uri) }
        }
        val picked = SubtitleMatch.pick(place.videoName, names.keys.toList())
        Result(picked.map { Found(names.getValue(it), it) }, place, granted)
    }

    /** The storage document uri of the video's folder, to start the folder picker there. */
    fun folderUri(place: Place): Uri? =
        place.folderDocId?.let { DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, it) }

    private fun grantedTrees(): List<Uri> = resolver.persistedUriPermissions
        .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) && it.uri.authority == EXTERNAL_STORAGE }
        .map { it.uri }

    private fun children(tree: Uri, docId: String): List<Pair<String, Uri>> {
        val list = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return resolver.query(list, cols, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    add(name to DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)))
                }
            }
        }.orEmpty()
    }

    private fun placeOf(video: Uri): Place? = when {
        video.scheme == "file" || video.scheme == null -> File(video.path ?: video.toString()).let { Place(it.name, null, it.parentFile) }
        video.authority == MediaStore.AUTHORITY -> mediaStorePlace(video)
        DocumentsContract.isDocumentUri(appContext, video) && video.authority == EXTERNAL_STORAGE -> {
            val id = DocumentsContract.getDocumentId(video)
            Place(id.substringAfterLast('/').substringAfter(':'), id.substringBeforeLast('/', id.substringBefore(':') + ":"), null)
        }
        else -> null
    }

    private fun mediaStorePlace(video: Uri): Place? {
        val cols = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.VOLUME_NAME)
        return resolver.query(video, cols, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val name = c.getString(0) ?: return null
            val relative = c.getString(1).orEmpty().trimEnd('/')
            Place(name, "${volumeId(c.getString(2))}:$relative", null)
        }
    }

    companion object {
        const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

        /** MediaStore's volume names map to the storage provider's root ids: "external_primary" is "primary", SD cards keep their id in upper case. */
        fun volumeId(volume: String?): String = when (volume) {
            null, MediaStore.VOLUME_EXTERNAL_PRIMARY, MediaStore.VOLUME_EXTERNAL -> "primary"
            else -> volume.uppercase()
        }

        /** A grant on [treeId] covers [folderId] when the folder is the tree or sits below it. */
        fun covers(treeId: String, folderId: String): Boolean =
            folderId == treeId || folderId.startsWith(if (treeId.endsWith(":")) treeId else "$treeId/")
    }
}

package app.player.core.data.library

/** A folder that holds at least one video directly (US-01.1). */
data class LibraryFolder(
    val key: String,
    val name: String,
    /** Path inside its volume for display, e.g. `Movies/Trips`. Empty for the volume root. */
    val relativePath: String,
    val volume: String,
    val videoCount: Int,
    /** Newest video in the folder, used for the folder's thumbnail. */
    val cover: LibraryVideo,
)

/** Groups videos by the folder they sit in directly. Subfolders are separate folders. */
fun List<LibraryVideo>.toFolders(): List<LibraryFolder> =
    groupBy { it.folder }.map { (key, videos) ->
        val relative = key.substringAfter('/').trimEnd('/')
        LibraryFolder(
            key = key,
            name = relative.substringAfterLast('/'),
            relativePath = relative,
            volume = videos.first().volume,
            videoCount = videos.size,
            cover = videos.maxBy { it.modifiedSec },
        )
    }

/** Folder key for a MediaStore row. Relative paths end with a slash; a file at the volume root has "". */
fun folderKey(volume: String, relativePath: String?): String = "$volume/${relativePath.orEmpty()}"

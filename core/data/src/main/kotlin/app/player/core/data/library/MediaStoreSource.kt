package app.player.core.data.library

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.provider.MediaStore.Video.Media

/** Where a scan reads videos from. MediaStore on a device, a fake in tests. */
fun interface VideoSource {
    /** Runs on a background thread. Returns every video the app may see. */
    fun scan(): List<LibraryVideo>
}

/**
 * Reads every video MediaStore knows on every external volume (internal storage, SD cards, USB).
 * MediaStore already leaves out pending and trashed files, folders holding `.nomedia`, and dot-files.
 * With partial media access it returns only the videos the viewer picked.
 */
class MediaStoreSource(context: Context) : VideoSource {
    private val resolver = context.applicationContext.contentResolver

    override fun scan(): List<LibraryVideo> {
        val projection = arrayOf(
            Media._ID, Media.VOLUME_NAME, Media.RELATIVE_PATH, Media.DATA, Media.DISPLAY_NAME,
            Media.SIZE, Media.DURATION, Media.WIDTH, Media.HEIGHT, Media.DATE_MODIFIED, Media.MIME_TYPE,
        )
        val cursor = resolver.query(Media.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, null, null, null)
            ?: return emptyList()
        return cursor.use { c ->
            val id = c.getColumnIndexOrThrow(Media._ID)
            val volume = c.getColumnIndexOrThrow(Media.VOLUME_NAME)
            val relative = c.getColumnIndexOrThrow(Media.RELATIVE_PATH)
            val data = c.getColumnIndexOrThrow(Media.DATA)
            val name = c.getColumnIndexOrThrow(Media.DISPLAY_NAME)
            val size = c.getColumnIndexOrThrow(Media.SIZE)
            val duration = c.getColumnIndexOrThrow(Media.DURATION)
            val width = c.getColumnIndexOrThrow(Media.WIDTH)
            val height = c.getColumnIndexOrThrow(Media.HEIGHT)
            val modified = c.getColumnIndexOrThrow(Media.DATE_MODIFIED)
            val mime = c.getColumnIndexOrThrow(Media.MIME_TYPE)
            ArrayList<LibraryVideo>(c.count).apply {
                while (c.moveToNext()) {
                    val rowId = c.getLong(id)
                    val vol = c.getString(volume) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                    add(
                        LibraryVideo(
                            id = rowId,
                            uri = ContentUris.withAppendedId(Media.getContentUri(vol), rowId).toString(),
                            volume = vol,
                            folder = folderKey(vol, c.getString(relative)),
                            path = c.getString(data),
                            name = c.getString(name) ?: "",
                            sizeBytes = c.getLong(size),
                            durationMs = c.getLong(duration),
                            width = c.getInt(width),
                            height = c.getInt(height),
                            modifiedSec = c.getLong(modified),
                            mimeType = c.getString(mime),
                        ),
                    )
                }
            }
        }
    }
}

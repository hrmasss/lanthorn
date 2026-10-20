package app.player.feature.subtitles

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/**
 * A one-folder documents provider for the test: stands in for the folder the viewer picks with
 * the system picker (US-42.1), so the import runs the same DocumentsContract path as on a phone.
 * The folder holds whatever the test copies into `cacheDir/picked-fonts`.
 */
class TestFontsProvider : DocumentsProvider() {
    private val dir get() = File(context!!.cacheDir, "picked-fonts")

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE)).apply { addRow(arrayOf(ROOT, ROOT, "Fonts")) }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = cursor().apply { add(documentId) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        cursor().apply { dir.listFiles()?.forEach { add("$ROOT/${it.name}") } }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(File(dir, documentId.substringAfter('/')), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun isChildDocument(parentDocumentId: String, documentId: String) = documentId.startsWith("$parentDocumentId/")

    private fun cursor() = MatrixCursor(arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE))

    private fun MatrixCursor.add(id: String) {
        val folder = id == ROOT
        addRow(arrayOf(id, if (folder) "fonts" else id.substringAfter('/'), if (folder) Document.MIME_TYPE_DIR else "font/ttf"))
    }

    companion object {
        const val AUTHORITY = "app.player.feature.subtitles.test.fonts"
        const val ROOT = "root"
    }
}

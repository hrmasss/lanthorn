package app.player.feature.library

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import app.player.core.data.library.LibraryVideo
import app.player.core.playerapi.PlayerIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Share, delete and rename for library videos. Writes go through MediaStore's own consent prompts
 * (one system prompt, US-10.5 and US-71.2), so the app never asks for broad storage access.
 */
class FileActions internal constructor(
    private val context: Context,
    private val launchSender: (IntentSenderRequest, (Boolean) -> Unit) -> Unit,
    private val onChanged: () -> Unit,
    private val scope: kotlinx.coroutines.CoroutineScope,
    /** Tells the viewer how an action ended; a toast in the app, recorded in tests. */
    private val notify: (message: Int) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
    /** The MediaStore rename; tests swap it to reach the permission paths. */
    private val write: ((Uri, String) -> Unit)? = null,
) {
    /** US-74: the system share sheet with these videos; the receiver may read them for this share only. */
    fun share(videos: List<LibraryVideo>) {
        if (videos.isEmpty()) return
        val uris = ArrayList(videos.map { it.uri.toUri() })
        val type = videos.map { it.mimeType ?: "video/*" }.distinct().singleOrNull() ?: "video/*"
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.setType(type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.action_share))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    /**
     * US-10.4: the info panel of US-37 for one file, shown by feature/info's own screen
     * ([PlayerIntents.showInfo]), so the library needs no dependency on it.
     */
    fun properties(video: LibraryVideo) {
        val intent = PlayerIntents.showInfo(context, video.uri.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            failed(R.string.properties_unavailable)
        }
    }

    /**
     * US-10.2: Android 11+ shows its own single confirmation naming the count, then deletes.
     * Android 10 has no batch prompt; [confirmedOnQ] says the app's own confirmation was given.
     */
    fun delete(videos: List<LibraryVideo>, confirmedOnQ: Boolean = false) {
        if (videos.isEmpty()) return
        val uris = videos.map { it.uri.toUri() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val request = MediaStore.createDeleteRequest(context.contentResolver, uris)
            ask(IntentSenderRequest.Builder(request.intentSender).build()) { ok ->
                if (ok) done(R.string.deleted) else failed(R.string.delete_denied)
            }
        } else if (confirmedOnQ) {
            scope.launch { deleteOneByOne(uris) }
        }
    }

    private suspend fun deleteOneByOne(uris: List<Uri>) {
        for ((i, uri) in uris.withIndex()) {
            val failure = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.delete(uri, null, null) }.exceptionOrNull()
            }
            if (failure is RecoverableSecurityException) {
                // Android 10 asks per file; continue from this one once allowed.
                ask(IntentSenderRequest.Builder(failure.userAction.actionIntent.intentSender).build()) { granted ->
                    if (granted) scope.launch { deleteOneByOne(uris.drop(i)) } else failed(R.string.delete_denied)
                }
                return
            }
            if (failure != null) return failed(R.string.delete_failed)
        }
        done(R.string.deleted)
    }

    /**
     * US-10.1: renames through MediaStore. The uri stays the same, so resume points and remembered
     * choices (keyed by uri) carry over. Asks for write access once if the app may not change the file.
     */
    fun rename(video: LibraryVideo, newName: String) {
        val uri = video.uri.toUri()
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { update(uri, newName) } }
            val error = result.exceptionOrNull()
            when {
                result.isSuccess -> done(R.string.renamed)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && error is SecurityException -> {
                    val request = MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
                    ask(IntentSenderRequest.Builder(request.intentSender).build()) { ok ->
                        if (ok) scope.launch {
                            val retry = withContext(Dispatchers.IO) { runCatching { update(uri, newName) } }
                            if (retry.isSuccess) done(R.string.renamed) else failed(R.string.rename_failed)
                        } else {
                            failed(R.string.rename_denied)
                        }
                    }
                }
                error is RecoverableSecurityException -> ask(
                    IntentSenderRequest.Builder(error.userAction.actionIntent.intentSender).build(),
                ) { ok -> if (ok) rename(video, newName) else failed(R.string.rename_denied) }
                else -> failed(R.string.rename_failed)
            }
        }
    }

    private fun update(uri: Uri, name: String) {
        write?.let { return it(uri, name) }
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }
        if (context.contentResolver.update(uri, values, null, null) != 1) error("not renamed")
    }

    /** US-71.2: every system access prompt comes with the reason, in one line. */
    private fun ask(request: IntentSenderRequest, then: (Boolean) -> Unit) {
        notify(R.string.write_access_reason)
        launchSender(request, then)
    }

    private fun done(message: Int) {
        notify(message)
        onChanged()
    }

    // US-10.5: never fail silently.
    private fun failed(message: Int) = notify(message)
}

@Composable
fun rememberFileActions(onChanged: () -> Unit): FileActions {
    val context = LocalContext.current
    val scope = rememberMainScope()
    var pending by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        pending?.invoke(r.resultCode == Activity.RESULT_OK)
        pending = null
    }
    return remember(context, scope) {
        FileActions(
            context,
            { request, then ->
                pending = then
                launcher.launch(request)
            },
            onChanged,
            scope,
        )
    }
}

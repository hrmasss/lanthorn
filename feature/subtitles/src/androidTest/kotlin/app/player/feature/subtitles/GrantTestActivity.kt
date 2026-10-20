package app.player.feature.subtitles

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.CompletableFuture

/** Opens the system folder picker at [start] and keeps the grant, as the subtitle panel does. */
class GrantTestActivity : ComponentActivity() {
    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        result.complete(tree)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pick.launch(start)
    }

    companion object {
        var start: Uri? = null
        var result = CompletableFuture<Uri?>()
    }
}

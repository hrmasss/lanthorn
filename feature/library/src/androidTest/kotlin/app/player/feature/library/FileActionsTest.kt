package app.player.feature.library

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.data.library.LibraryVideo
import app.player.core.playerapi.PlayerIntents
import app.player.core.testing.Ears
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The intents the file actions send. The info screen itself is proven in app's `DecoderUiTest.f_infoScreenForAFile`. */
@RunWith(AndroidJUnit4::class)
class FileActionsTest {
    private val started = mutableListOf<Intent>()
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun startActivity(intent: Intent) { started += intent }
        override fun startActivity(intent: Intent, options: Bundle?) { started += intent }
    }
    private val actions = FileActions(context, { _, _ -> }, {}, MainScope())

    private fun video(id: Long, mime: String = "video/mp4") = LibraryVideo(
        id, "content://media/external_primary/video/media/$id", "external_primary", "external_primary/Movies/",
        null, "Clip $id.mp4", 1000, 60_000, 1920, 1080, id, mime,
    )

    @Ears("US-10.4")
    @Test fun us_10_4_properties_open_the_info_screen_for_that_file() {
        actions.properties(video(7))
        val intent = started.single()
        assertEquals(PlayerIntents.ACTION_SHOW_INFO, intent.action)
        assertEquals(Uri.parse("content://media/external_primary/video/media/7"), intent.data)
        assertEquals((context as Context).packageName, intent.`package`)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Ears("US-74.1", "US-74.2")
    @Test fun us_74_share_sends_content_uris_with_their_type_and_a_read_grant() {
        actions.share(listOf(video(1), video(2)))
        val chooser = started.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals("video/mp4", send.type)
        @Suppress("DEPRECATION")
        val uris = send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(listOf(1L, 2L).map { Uri.parse("content://media/external_primary/video/media/$it") }, uris)
        // US-74.2: read access for these files only, for this share; no write grant.
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(2, send.clipData!!.itemCount)
    }

    @Ears("US-74.1")
    @Test fun us_74_one_video_shares_as_a_single_stream() {
        actions.share(listOf(video(3, "video/x-matroska")))
        @Suppress("DEPRECATION")
        val send = started.single().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("video/x-matroska", send.type)
    }
}

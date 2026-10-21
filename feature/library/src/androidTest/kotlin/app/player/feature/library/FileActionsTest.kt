package app.player.feature.library

import android.content.ContentUris
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.player.core.data.library.LibraryVideo
import app.player.core.playerapi.PlayerIntents
import app.player.core.testing.Ears
import app.player.core.testing.evidenceShot
import kotlinx.coroutines.MainScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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

    @Ears("US-10.5")
    @Test fun us_10_5_a_denied_rename_is_said_not_silent() {
        val said = LinkedBlockingQueue<Int>()
        var prompts = 0
        val denying = FileActions(
            context, { _, then -> prompts++; then(false) }, {}, MainScope(),
            notify = { said += it },
            write = { _, _ -> throw SecurityException("not ours") },
        )
        InstrumentationRegistry.getInstrumentation().runOnMainSync { denying.rename(video(7), "New name.mp4") }
        assertEquals(R.string.write_access_reason, said.poll(10, TimeUnit.SECONDS))
        assertEquals(R.string.rename_denied, said.poll(10, TimeUnit.SECONDS))
        assertEquals("one system prompt", 1, prompts)
    }

    @Ears("US-10.5")
    @Test fun us_10_5_a_denied_delete_is_said_not_silent() {
        val said = LinkedBlockingQueue<Int>()
        val denying = FileActions(context, { _, then -> then(false) }, {}, MainScope(), notify = { said += it })
        InstrumentationRegistry.getInstrumentation().runOnMainSync { denying.delete(listOf(video(7), video(8))) }
        assertEquals(R.string.write_access_reason, said.poll(10, TimeUnit.SECONDS))
        assertEquals(R.string.delete_denied, said.poll(10, TimeUnit.SECONDS))
    }

    @Ears("US-71.2")
    @Test fun us_71_2_a_file_the_app_may_change_is_renamed_without_any_prompt() {
        val said = LinkedBlockingQueue<Int>()
        var prompts = 0
        val allowed = FileActions(
            context, { _, then -> prompts++; then(true) }, {}, MainScope(),
            notify = { said += it },
            write = { _, _ -> },
        )
        InstrumentationRegistry.getInstrumentation().runOnMainSync { allowed.rename(video(7), "New name.mp4") }
        assertEquals(R.string.renamed, said.poll(10, TimeUnit.SECONDS))
        assertEquals("no access prompt for a file the app may change", 0, prompts)
        assertEquals(null, said.poll(500, TimeUnit.MILLISECONDS))
    }

    @Ears("US-71.2")
    @Test fun us_71_2_access_is_asked_only_when_needed_and_the_reason_comes_first_in_one_line() {
        val events = LinkedBlockingQueue<String>()
        val reason = context.getString(R.string.write_access_reason)
        var writes = 0
        val asking = FileActions(
            context, { _, then -> events += "prompt"; then(true) }, {}, MainScope(),
            notify = { events += context.getString(it) },
            // Not ours until the viewer allows it: the first write fails, the retry after the prompt works.
            write = { _, _ -> if (writes++ == 0) throw SecurityException("not ours") },
        )
        InstrumentationRegistry.getInstrumentation().runOnMainSync { asking.rename(video(7), "New name.mp4") }
        assertEquals(reason, events.poll(10, TimeUnit.SECONDS))
        assertEquals("prompt", events.poll(10, TimeUnit.SECONDS))
        assertEquals(context.getString(R.string.renamed), events.poll(10, TimeUnit.SECONDS))
        assertTrue("the reason is one line", reason.isNotBlank() && '\n' !in reason)
    }

    /**
     * The system's one delete confirmation names the count. The two videos are added to MediaStore by
     * the shell, so they belong to another app and Android must ask.
     */
    @Ears("US-10.2")
    @Test fun us_10_2_deleting_two_videos_asks_once_naming_the_count() {
        val names = listOf("ears-delete-1.mp4", "ears-delete-2.mp4")
        val uris = names.map(::shellVideo)
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            var prompts = 0
            lateinit var activity: ComponentActivity
            scenario.onActivity { activity = it }
            val actions = FileActions(
                activity,
                { request, _ ->
                    prompts++
                    activity.startIntentSenderForResult(request.intentSender, 7, request.fillInIntent, 0, 0, 0)
                },
                {}, MainScope(), notify = {},
            )
            val videos = uris.mapIndexed { i, uri ->
                video(ContentUris.parseId(uri)).copy(uri = uri.toString(), name = names[i])
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { actions.delete(videos) }
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val dialog = device.wait(Until.findObject(By.textContains("2 videos")), 15_000)
            evidenceShot("delete-confirmation")
            assertNotNull("no system confirmation naming 2 videos", dialog)
            assertEquals("one confirmation for both", 1, prompts)
            device.pressBack()
            device.wait(Until.gone(By.textContains("2 videos")), 5_000)
            uris.forEach { assertTrue("deleted without consent: $it", exists(it)) }
        } finally {
            scenario.close()
            names.forEach { shell("content delete --uri $VIDEOS --where _display_name='$it'") }
        }
    }

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
    }

    private fun shellVideo(name: String): Uri {
        shell("content delete --uri $VIDEOS --where _display_name='$name'")
        shell("content insert --uri $VIDEOS --bind _display_name:s:$name --bind mime_type:s:video/mp4 --bind relative_path:s:Movies/EarsTest/")
        val id = Regex("_id=(\\d+)").find(shell("content query --uri $VIDEOS --projection _id --where _display_name='$name'"))
            ?.groupValues?.get(1)?.toLong() ?: error("shell could not add $name to MediaStore")
        return ContentUris.withAppendedId(Uri.parse(VIDEOS), id)
    }

    private fun exists(uri: Uri) =
        shell("content query --uri $VIDEOS --projection _id --where _id=${ContentUris.parseId(uri)}").contains("_id=")

    private companion object {
        const val VIDEOS = "content://media/external_primary/video/media"
    }
}

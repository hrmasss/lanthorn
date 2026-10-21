package app.player.feature.library

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.testing.Ears
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** US-02.4: a thumbnail cached on disk is reused on the next open, with no decode of the video. */
@RunWith(AndroidJUnit4::class)
class ThumbnailCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "thumbs")
    // The uri is no real file: a decode could not succeed, so a picture can only come from the cache.
    private val video = testVideo(5)
    private val file = File(dir, "${video.id}_${video.modifiedSec}.jpg")

    @After fun tearDown() { file.delete() }

    private fun cacheOne() {
        dir.mkdirs()
        val bitmap = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF336699.toInt()) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    @Ears("US-02.4")
    @Test fun us_02_4_next_open_reuses_the_cached_thumbnail_without_decoding() {
        assertTrue("caching is on by default", LibraryPrefs().cacheThumbnails)
        cacheOne()
        // A new loader is a new app start: nothing in memory.
        val loader = ThumbnailLoader(context)
        val bitmap = runBlocking { loader.load(video) }
        assertNotNull(bitmap)
        assertEquals(48, bitmap!!.width)
        assertEquals(1, loader.stats.disk.get())
        assertEquals(0, loader.stats.decoded.get())
        // Then the memory cache, still no decode.
        runBlocking { loader.load(video) }
        assertEquals(1, loader.stats.memory.get())
        assertEquals(0, loader.stats.decoded.get())
    }

    @Ears("US-02.4")
    @Test fun us_02_4_with_caching_off_the_disk_cache_is_not_used() {
        cacheOne()
        val loader = ThumbnailLoader(context).apply { cacheOnDisk = false }
        assertNull(runBlocking { loader.load(video) })
        assertEquals(0, loader.stats.disk.get())
    }
}

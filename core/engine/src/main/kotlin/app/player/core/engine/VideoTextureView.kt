package app.player.core.engine

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView

/**
 * A video view drawn through the app's window, for small views over the main video (the seek
 * preview). Unlike a SurfaceView it stacks with Compose content and can be hidden with alpha while
 * its surface stays attached, so mpv's video output is ready before it is shown.
 */
class VideoTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    var engine: PlayerEngine? = null
        set(value) {
            if (field === value) return
            surface?.let { field?.detachSurface() }
            field = value
            value?.reportsShownFrames = true
            surface?.let { value?.attachSurface(it) }
        }

    private var surface: Surface? = null

    init {
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val s = Surface(texture)
        surface = s
        engine?.attachSurface(s)
        engine?.setSurfaceSize(width, height)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        engine?.setSurfaceSize(width, height)
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        engine?.detachSurface()
        surface?.release()
        surface = null
        return true
    }

    // A new frame is on screen: the preview engine sends its next seek only now.
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        engine?.frameShown()
    }
}

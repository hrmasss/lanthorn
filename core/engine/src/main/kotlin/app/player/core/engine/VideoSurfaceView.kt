package app.player.core.engine

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * The video surface. A SurfaceView, so the compositor can put video on a hardware overlay
 * instead of drawing it through the app's window. Wire it to one [PlayerEngine].
 */
class VideoSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    var engine: PlayerEngine? = null
        set(value) {
            if (field === value) return
            if (attached) field?.detachSurface()
            field = value
            if (attached) value?.attachSurface(holder.surface)
        }

    private var attached = false

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        attached = true
        engine?.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        engine?.setSurfaceSize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        attached = false
        engine?.detachSurface()
    }
}

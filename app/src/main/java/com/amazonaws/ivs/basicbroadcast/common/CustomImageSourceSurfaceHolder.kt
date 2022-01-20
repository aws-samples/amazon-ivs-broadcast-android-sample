package com.amazonaws.ivs.basicbroadcast.common

import android.graphics.Canvas
import android.graphics.Rect
import android.view.Surface
import android.view.SurfaceHolder

/**
 * This class is a simple wrapper for the Surface provided by BroadcastSession.createCustomImageSource().
 * This makes it easier to pass the custom Surface to other classes that expect a SurfaceHolder
 * instead of a Surface, e.g. MediaPlayer.
 *
 */
class CustomImageSourceSurfaceHolder(surface: Surface) : SurfaceHolder {
    private var surface: Surface = surface

    override fun addCallback(callback: SurfaceHolder.Callback?) {
        // no-op.
    }

    override fun removeCallback(callback: SurfaceHolder.Callback?) {
        // no-op.
    }

    override fun isCreating(): Boolean {
        // Surface is already created when CustomSurfaceHolder is constructed
        return false
    }

    override fun setType(type: Int) {
        // no-op.
    }

    override fun setFixedSize(width: Int, height: Int) {
        // no-op.
    }

    override fun setSizeFromLayout() {
        // no-op.
    }

    override fun setFormat(format: Int) {
        // no-op.
    }

    override fun setKeepScreenOn(screenOn: Boolean) {
        // no-op.
    }

    override fun lockCanvas(): Canvas {
        return this.surface.lockCanvas(null)
    }

    override fun lockCanvas(dirty: Rect?): Canvas {
        return this.surface.lockCanvas(dirty)
    }

    override fun unlockCanvasAndPost(canvas: Canvas?) {
        this.surface.unlockCanvasAndPost(canvas)
    }

    override fun getSurfaceFrame(): Rect {
        return Rect()
    }

    override fun getSurface(): Surface {
        return this.surface
    }

}

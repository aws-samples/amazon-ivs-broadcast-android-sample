package com.amazonaws.ivs.basicbroadcast.common

import android.graphics.Canvas
import android.graphics.Rect
import android.view.Surface
import android.view.SurfaceHolder

/**
 * This class is a simple wrapper for the custom image source's Surface provided by the
 * broadcast session when calling createCustomImageSource(). This allows us to provide the Surface
 * to other Android objects that require a SurfaceHolder, such as the MediaPlayer.
 */
class CustomSurfaceHolder(surface: Surface) : SurfaceHolder {
    private var surface: Surface = surface

    override fun addCallback(callback: SurfaceHolder.Callback?) {
        // no-op.
    }

    override fun removeCallback(callback: SurfaceHolder.Callback?) {
        // no-op.
    }

    override fun isCreating(): Boolean {
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
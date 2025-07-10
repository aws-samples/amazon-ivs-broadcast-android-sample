package com.amazonaws.ivs.basicbroadcast.viewModel

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.widget.LinearLayout
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.amazonaws.ivs.basicbroadcast.common.*
import com.amazonaws.ivs.broadcast.*

private const val TAG = "AmazonIVS"

class MixerViewModel(private val context: Application) : ViewModel() {

    private var deviceDiscovery: DeviceDiscovery? = null
    private var mixedImageDevice: MixedImageDevice? = null
    private var player: MediaPlayer? = null

    private var cameraIsSmall: Boolean = true

    private var cameraSource: MixedImageDeviceSource? = null
    private var contentSource: MixedImageDeviceSource? = null
    private var logoSource: MixedImageDeviceSource? = null

    val preview = MutableLiveData<ImagePreviewView>()
    val clearPreview = MutableLiveData<Boolean>()

    private companion object MixerGuide {
        const val BORDER_WIDTH: Float = 10f
        val bigSize: BroadcastConfiguration.Vec2 = BroadcastConfiguration.Vec2(1280f, 720f)
        val smallSize: BroadcastConfiguration.Vec2 = BroadcastConfiguration.Vec2(320f, 180f)
        val bigPosition: BroadcastConfiguration.Vec2 = BroadcastConfiguration.Vec2(0f, 0f)
        val smallPositionBottomLeft: BroadcastConfiguration.Vec2 = BroadcastConfiguration.Vec2(BORDER_WIDTH, bigSize.y - smallSize.y - BORDER_WIDTH)
        val smallPositionTopRight: BroadcastConfiguration.Vec2 = BroadcastConfiguration.Vec2(bigSize.x - smallSize.x - BORDER_WIDTH, BORDER_WIDTH)
        val smallPositionBottomRight: BroadcastConfiguration.Vec2 = BroadcastConfiguration.Vec2(bigSize.x - smallSize.x - BORDER_WIDTH, bigSize.y - smallSize.y - BORDER_WIDTH)
    }

    /**
     * Create the mixed image device
     */
    fun createMixedImageDevice(logo: Bitmap, content: Uri) {
        mixedImageDevice?.release()

        // Create a custom configuration at 720p60.
        val mixedImageDeviceConfig = MixedImageDeviceConfiguration().apply {
            size = bigSize
            targetFramerate = 60
            setEnableTransparency(true)
        }

        // This source will hold the camera and start in the bottom left corner of the stream. It will move during the transition.
        val cameraConfig = MixedImageDeviceSourceConfiguration().apply {
            size = smallSize
            position = smallPositionBottomLeft
            zIndex = 2
        }

        // This source will hold custom content (in this example, a looping mp4 file) and take up the entire stream. It will move during the transition.
        val contentConfig = MixedImageDeviceSourceConfiguration().apply {
            size = bigSize
            position = bigPosition
            zIndex = 1
        }

        // This source will be a logo-based watermark and sit in the bottom right corner of the stream. It will not move around.
        val logoConfig = MixedImageDeviceSourceConfiguration().apply {
            size = BroadcastConfiguration.Vec2(smallSize.y, smallSize.y) // 1:1 aspect
            position = BroadcastConfiguration.Vec2(bigSize.x - smallSize.y - BORDER_WIDTH, smallPositionBottomRight.y)
            zIndex = 3
            alpha = 0.7f
        }

        val deviceDiscovery = DeviceDiscovery(context)
        val mixedImageDevice = deviceDiscovery.createMixedImageDevice(mixedImageDeviceConfig)

        // Find the first camera.
        val camera = deviceDiscovery.listLocalDevices().firstNotNullOfOrNull { it as? CameraSource }
        if (camera != null) {
            val cameraSource = MixedImageDeviceSource(cameraConfig, camera)
            mixedImageDevice.addSource(cameraSource)
            this.cameraSource = cameraSource
        }

        // Second, create a custom image input source for the logo.
        val logoSurfaceSource = deviceDiscovery.createImageInputSource(BroadcastConfiguration.Vec2(logo.width.toFloat(), logo.height.toFloat()))
        val logoSurface = logoSurfaceSource.inputSurface
        val canvas = logoSurface!!.lockCanvas(null)
        canvas.drawBitmap(logo, 0f, 0f, null)
        logoSurface.unlockCanvasAndPost(canvas)
        val logoSource = MixedImageDeviceSource(logoConfig, logoSurfaceSource)
        mixedImageDevice.addSource(logoSource)

        // Third, create a custom image input source for the mp4 content.
        val contentSurfaceSource = deviceDiscovery.createImageInputSource(contentConfig.size)
        val contentSurface = contentSurfaceSource.inputSurface
        player = MediaPlayer().apply {
            setDataSource(context, content)
            prepare()
            setDisplay(CustomImageSourceSurfaceHolder(contentSurface!!))
            setOnPreparedListener {
                start()
                isLooping = true
            }
        }
        val contentSource = MixedImageDeviceSource(contentConfig, contentSurfaceSource)
        mixedImageDevice.addSource(contentSource)

        Log.d(TAG, "Displaying composite preview")
        try {
            mixedImageDevice.previewView?.run {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                clearPreview.value = true
                preview.value = this
            }
        } catch (e: BroadcastException) {
            Log.e(TAG, "Preview display exception $e")
        }

        this.mixedImageDevice = mixedImageDevice
        this.deviceDiscovery = deviceDiscovery
        this.contentSource = contentSource
        this.logoSource = logoSource
    }

    fun destroyResources() {
        (contentSource?.device as? CustomImageSource)?.release()
        (logoSource?.device as? CustomImageSource)?.release()

        player?.release()
        mixedImageDevice?.release()
        deviceDiscovery?.release()
        player = null
        mixedImageDevice = null
        deviceDiscovery = null
    }

    /**
     * Swap the size and position of the camera and content sources.
     */
    fun swapSources() {
        Log.d(TAG, "Swapping the camera and content sources")

        val cameraSource = this.cameraSource
        val contentSource = this.contentSource
        val mixedImageDevice = this.mixedImageDevice

        if (cameraSource == null || contentSource == null || mixedImageDevice == null) {
            return
        }

        // Update source configurations to their new state.
        cameraSource.configuration.let { config ->
            config.position = if (cameraIsSmall) bigPosition else smallPositionBottomLeft
            config.size = if (cameraIsSmall) bigSize else smallSize
            config.zIndex = (if (cameraIsSmall) 1 else 2)
        }
        contentSource.configuration.let { config ->
            config.position = if (cameraIsSmall) smallPositionTopRight else bigPosition
            config.size = if (cameraIsSmall) smallSize else bigSize
            config.zIndex = (if (cameraIsSmall) 2 else 1)
        }
        cameraIsSmall = cameraIsSmall.not()

        // Transition the sources to their new states over a 0.5 duration
        cameraSource.transitionToConfiguration(cameraSource.configuration, 500, null)
        contentSource.transitionToConfiguration(contentSource.configuration, 500, null)
    }
}

package com.amazonaws.ivs.basicbroadcast.viewModel

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.amazonaws.ivs.basicbroadcast.R
import com.amazonaws.ivs.basicbroadcast.common.*
import com.amazonaws.ivs.broadcast.*

private const val TAG = "AmazonIVS"

class MixerViewModel(private val context: Application) : ViewModel() {

    private var session: BroadcastSession? = null
    private var player: MediaPlayer? = null

    private var cameraIsSmall: Boolean = true

    private var cameraSlot: BroadcastConfiguration.Mixer.Slot? = null
    private var contentSlot: BroadcastConfiguration.Mixer.Slot? = null
    private var logoSlot: BroadcastConfiguration.Mixer.Slot? = null

    val preview = MutableLiveData<ImagePreviewView>()
    val clearPreview = MutableLiveData<Boolean>()

    private companion object MixerGuide {
        const val CAMERA_SLOT_NAME: String = "camera"
        const val CONTENT_SLOT_NAME: String = "content"
        const val LOGO_SLOT_NAME: String = "logo"
        const val BORDER_WIDTH: Float = 10f
        val bigSize: BroadcastConfiguration.Vec2 = BroadcastConfiguration.Vec2(1280f, 720f)
        val smallSize: BroadcastConfiguration.Vec2 = BroadcastConfiguration.Vec2(320f, 180f)
        val bigPosition: BroadcastConfiguration.Vec2 = BroadcastConfiguration.Vec2(0f, 0f)
        val smallPositionBottomLeft: BroadcastConfiguration.Vec2 = BroadcastConfiguration.Vec2(BORDER_WIDTH, bigSize.y - smallSize.y - BORDER_WIDTH)
        val smallPositionTopRight: BroadcastConfiguration.Vec2 = BroadcastConfiguration.Vec2(bigSize.x - smallSize.x - BORDER_WIDTH, BORDER_WIDTH)
        val smallPositionBottomRight: BroadcastConfiguration.Vec2 = BroadcastConfiguration.Vec2(bigSize.x - smallSize.x - BORDER_WIDTH, bigSize.y - smallSize.y - BORDER_WIDTH)
    }

    /**
     * Create and start new session
     */
    fun createSession(logo: Bitmap, content: Uri) {
        session?.release()

        // Create a custom configuration at 720p60.
        val config = BroadcastConfiguration().apply {
            video.size = bigSize
            video.targetFramerate = 60
            video.enableTransparency(true)

            // This slot will hold the camera and start in the bottom left corner of the stream. It will move during the transition.
            cameraSlot = BroadcastConfiguration.Mixer.Slot.with {
                it.size = smallSize
                it.aspect = BroadcastConfiguration.AspectMode.FIT
                it.position = smallPositionBottomLeft
                it.setzIndex(2)
                it.preferredVideoInput = Device.Descriptor.DeviceType.CAMERA
                it.name = CAMERA_SLOT_NAME

                return@with it
            }

            // This slot will hold custom content (in this example, a looping mp4 file) and take up the entire stream. It will move during the transition.
            contentSlot  = BroadcastConfiguration.Mixer.Slot.with {
                it.size = bigSize
                it.position = bigPosition
                it.setzIndex(1)
                it.name = CONTENT_SLOT_NAME

                return@with it
            }

            // This slot will be a logo-based watermark and sit in the bottom right corner of the stream. It will not move around.
            logoSlot  = BroadcastConfiguration.Mixer.Slot.with {
                it.size = BroadcastConfiguration.Vec2(smallSize.y, smallSize.y) // 1:1 aspect
                it.position = BroadcastConfiguration.Vec2(bigSize.x - smallSize.y - BORDER_WIDTH, smallPositionBottomRight.y)
                it.setzIndex(3)
                it.transparency = 0.3f
                it.name = LOGO_SLOT_NAME

                return@with it
            }

            mixer.slots = arrayOf(cameraSlot, contentSlot, logoSlot)
        }

        BroadcastSession(context, null, config, null).apply {
            session = this
            Log.d(TAG, "Broadcast session ready: $isReady")
            if (!isReady) {
                Log.d(TAG, "Broadcast session not ready")
                Toast.makeText(context, context.getString(R.string.error_create_session), Toast.LENGTH_SHORT).show()
                return
            }

            // Attach devices to each slot manually based on the slot names.
            // Find the first front camera.
            val devices = BroadcastSession.listAvailableDevices(context).filter {
                it.position == Device.Descriptor.Position.FRONT && it.type == Device.Descriptor.DeviceType.CAMERA
            }
            if (devices.isNotEmpty()) {
                try {
                    // Then, we attach the front camera and on completion, bind it to the camera slot.
                    // Note that bindToPreference is FALSE, which gives us full control over binding the device to the slot. This also means
                    // that we are responsible for binding the device to a slot once the device is attached.
                    // (When bindToPreference is TRUE, as part of attaching the device, the broadcast session will also try to bind the device to a
                    // slot with a matching type preference.)
                    attachDevice(devices.first(), false) {
                        val success: Boolean = this.mixer?.bind(it, CAMERA_SLOT_NAME) == true

                        // Error-checking. The most common source of this error is that there is no slot
                        // with the name provided.
                        if (!success) Toast.makeText(context, context.getString(R.string.error_failed_to_bind_to_slot), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Camera exception: $e")
                }
            }

            // Second, create a custom image input source for the logo.
            val logoSurfaceSource = this.createImageInputSource()
            val logoSurface = logoSurfaceSource.inputSurface
            val canvas = logoSurface!!.lockCanvas(null)
            canvas.drawBitmap(logo, 0f, 0f, null)
            logoSurface.unlockCanvasAndPost(canvas)
            // Bind it to the logo slot.
            awaitDeviceChanges {
                val success: Boolean = this.mixer?.bind(logoSurfaceSource, LOGO_SLOT_NAME) == true

                // Error-checking. The most common source of this error is that there is no slot
                // with the name provided.
                if (!success) Toast.makeText(context, context.getString(R.string.error_failed_to_bind_to_slot), Toast.LENGTH_SHORT).show()
            }

            // Third, create a custom image input source for the mp4 content.
            val contentSurfaceSource = this.createImageInputSource()
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
            // Bind it to the content slot.
            awaitDeviceChanges {
                val success: Boolean = this.mixer?.bind(contentSurfaceSource, CONTENT_SLOT_NAME) == true

                // Error-checking. The most common source of this error is that there is no slot
                // with the name provided.
                if (!success) Toast.makeText(context, context.getString(R.string.error_failed_to_bind_to_slot), Toast.LENGTH_SHORT).show()
            }

            // This creates a preview of the composited output stream, not an individual source. Because of this there is small
            // amount of delay in the preview since it has to go through a render cycle to composite the sources together.
            // It is also important to note that because our configuration is for a landscape stream using the "fit" aspect mode
            // there will be aggressive letterboxing when holding a mobile phone in portrait. Rotating to landscape or using an tablet
            // will provide a larger preview, though the only change is the scaling.
            awaitDeviceChanges {
                displayPreview()
            }
        }
    }

    fun endSession() {
        player?.release()
        session?.release()
        player = null
        session = null
    }

    /**
     * Swap the size and position of the camera and content slots.
     */
    fun swapSlots() {
        Log.d(TAG, "Swapping the camera and content slots")

        session?.run {
            // Update slot configurations to their new state.
            cameraIsSmall.apply {
                cameraSlot?.let { slot ->
                    slot.position = if (cameraIsSmall) bigPosition else smallPositionBottomLeft
                    slot.size = if (cameraIsSmall) bigSize else smallSize
                    slot.setzIndex(if (cameraIsSmall) 1 else 2)
                }
                contentSlot?.let { slot ->
                    slot.position = if (cameraIsSmall) smallPositionTopRight else bigPosition
                    slot.size = if (cameraIsSmall) smallSize else bigSize
                    slot.setzIndex(if (cameraIsSmall) 2 else 1)
                }
                cameraIsSmall = !this
            }

            // Transition the slots to their new states over a 0.5 duration.
            // Two common sources of failure is when the slot does not exist,
            // or the new configuration slot name does not match the slot name provided to the method.
            mixer?.transition(CAMERA_SLOT_NAME, cameraSlot!!, 0.5, null)
            mixer?.transition(CONTENT_SLOT_NAME, contentSlot!!, 0.5, null)
        }
    }

    /**
     * Display session's composite preview
     */
    private fun displayPreview() {
        Log.d(TAG, "Displaying composite preview")
        try {
            session?.previewView?.run {
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
    }
}

package com.amazonaws.ivs.basicbroadcast.common

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.WindowManager
import com.amazonaws.ivs.broadcast.SurfaceSource
import java.lang.Exception
import java.lang.Math.toRadians
import java.util.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * This class manages the front camera device so that we can use it as a custom image source for the
 * broadcast session. We will add a simple sepia filter to the camera.
 */
class CameraManager(private val context: Context) {

    private val handlerThread: HandlerThread
    private val handler: Handler

    private var device: CameraDevice? = null
    private var deviceId: String = ""
    private var captureSession: CameraCaptureSession? = null
    private var characteristics: CameraCharacteristics? = null
    private var captureBuilder: CaptureRequest.Builder? = null

    private var size: Size = Size(0, 0)
    private var lensRotationInRadians: Float = 0f
    private var lastNotifiedOrientation = 0.0

    // The SurfaceSource provided by the broadcast session for use with the custom image source.
    private var surface: SurfaceSource? = null

    init {
        HandlerThread("com.amazonaws.ivs.basicbroadcast.CameraManager").apply {
            handlerThread = this
            this.start()
            handler = Handler(this.looper)
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Find the first usable front-facing camera and note the relevant characteristics.
        for (id in manager.cameraIdList) {
            try {
                characteristics = manager.getCameraCharacteristics(id)
            } catch (e: CameraAccessException) {
                continue
            }

            // We will need its best-fit supported size as well as its lens rotation so that we can
            // manage these manually in the application (since the broadcast SDK will not be handling this
            // for us, as we're not using the preset camera).
            if (characteristics?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                val size = getSupportedSize()
                val lensRotationInRadians = getLensRotation()

                if (size != null) {
                    deviceId = id
                    this.size = size
                    this.lensRotationInRadians = lensRotationInRadians
                    break
                }
            }
        }

        if (deviceId.isEmpty()) {
            Log.e(TAG, "Unable to find valid front-facing camera on this device")
        }
    }

    @SuppressLint("MissingPermission") // Permission check added in API 23
    fun open(surface: SurfaceSource) {
        // Set the correct rotation and size for the surface, based on the camera characteristics.
        surface.setRotation(lensRotationInRadians)
        surface.setSize(size.width, size.height)

        // Detect orientation changes so that we can re-rotate the camera as needed.
        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }

        this.surface = surface

        // Camera APIs can take some time, so we'll put these calls on a separate thread
        // to avoid blocking the main UI thread.
        handler.post {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            try {
                manager.openCamera(deviceId, cameraStateCallback, handler)
            } catch (e: CameraAccessException) {
                Log.d(TAG, "Failed to open camera")
            }
        }
    }

    fun release() {
        orientationListener.disable()
        safelyCloseDevice()
        handlerThread.quitSafely()
    }

    /**
     * Retrieve the best fit size that this camera supports.
     */
    private fun getSupportedSize(): Size? {
        val streamConfigurationMap: StreamConfigurationMap? =
            characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = streamConfigurationMap?.getOutputSizes(
            SurfaceTexture::class.java
        ) ?: return null

        var size = Size(1, 1)
        var curAspect = 1f
        val target = 1920f / 1080f
        val targetPixels = (1920 * 1080).toFloat()

        sizes.forEach { sz ->
            val aspect = sz.width.toFloat() / sz.height.toFloat()
            val pixels = sz.width * sz.height

            val isAspectBetter = abs(target - aspect) <= abs(target - curAspect)
            val isResolutionBetter = pixels >= size.width * size.height
            val isResolutionWithinBounds = pixels <= targetPixels

            if (isAspectBetter && isResolutionBetter && isResolutionWithinBounds) {
                curAspect = aspect
                size = sz
            }
        }

        return size
    }

    /**
     *  Retrieve the camera's sensor orientation. We will need to use this value to manually rotate
     *  the custom surface returned by BroadcastSession.createImageInputSource later on so that the preview
     *  appears right-side-up relative to the physical device's natural orientation.
     */
    private fun getLensRotation(): Float {
        val rotation = Objects.requireNonNull<Int>(characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION))
            .toDouble()

        // Rotation needs to be reversed for the front camera.
        return -toRadians(rotation).toFloat()
    }

    @Throws(CameraAccessException::class)
    private fun setupSession() {
        device?.let {
            // Initial configuration for the capture request.
            it.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                captureBuilder = this

                // We're adding a sepia filter to the camera.
                // See other simple color filters here: https://developer.android.com/reference/android/hardware/camera2/CaptureRequest.html?hl=ru#CONTROL_EFFECT_MODE
                // Alternatively, it may be necessary to use an external library that provides a beauty filter with the Camera2 API.
                this.set(
                    CaptureRequest.CONTROL_EFFECT_MODE,
                    CaptureRequest.CONTROL_EFFECT_MODE_SEPIA
                )

                // Set the target to the custom surface provided by the broadcast session.
                this.addTarget(surface!!.inputSurface!!)
            }

            it.createCaptureSession(listOf(surface!!.inputSurface!!), sessionStateCallback, null)

        }
    }

    @Synchronized
    private fun safelyCloseDevice() {
        captureSession = null
        device?.close()
        device = null
    }

    private val sessionStateCallback: CameraCaptureSession.StateCallback =
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                try {

                    // Some additional capture request configurations.

                    captureBuilder?.let {
                        it.set(
                            CaptureRequest.CONTROL_MODE,
                            CameraMetadata.CONTROL_MODE_AUTO
                        )

                        // Select the best match for fps.
                        val fpsRanges =
                            characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        var range = Range(1, 15)
                        if (fpsRanges != null) {
                            for (fps in fpsRanges) {
                                if (abs(fps.upper - fps.lower) >= abs(range.upper - range.lower) && fps.upper > range.upper) {
                                    range = fps
                                }
                            }
                        }
                        Log.d(
                            "AmazonIVS",
                            String.format("Using fps range %d->%d", range.lower, range.upper)
                        )

                        it.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                        session.setRepeatingRequest(it.build(), null, handler)
                    }
                } catch (e: Exception) {
                    Log.e("AmazonIVS", "Caught $e")
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e("AmazonIVS", "Camera Configuration failed")
            }
        }

    private val cameraStateCallback: CameraDevice.StateCallback =
        object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                try {
                    setupSession()
                } catch (e: CameraAccessException) {
                    // handle access exception
                    Log.d("AmazonIVS", "CameraCaptureSession Caught exception $e")
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                Log.d("AmazonIVS", "Camera disconnected")
                handler.post { safelyCloseDevice() }
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e("AmazonIVS", String.format("Camera error %d", error))
                handler.post { safelyCloseDevice() }
            }
        }

    private val orientationListener: OrientationEventListener =
    object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            var orientation = orientation
            if (orientation == ORIENTATION_UNKNOWN) {
                // We do not know what the current device orientation is, just exit and let the picture be in
                // its prior orientation.
                return
            }
            var rads = toRadians(orientation.toDouble())
            val distance = abs(
                atan2(
                    sin(rads - lastNotifiedOrientation),
                    cos(rads - lastNotifiedOrientation)
                )
            )
            if (distance < Math.PI / 8 * 3) {
                // We don't want to bother checking if the change isn't approaching a quarter of a rotation (PI/2 radians)
                // because it would mean constantly asking the device manager for the screen's rotation
                return
            }
            val display =
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            orientation = display.rotation

            // Rotation needs to be reversed for the front camera
            rads = -(orientation * (Math.PI / 2.0) % (Math.PI * 2.0))
            if (rads == lastNotifiedOrientation) {
                return
            }

            lastNotifiedOrientation = rads

            // Update the surface's rotation
            surface?.setRotation(lensRotationInRadians + rads.toFloat())
        }
    }
}

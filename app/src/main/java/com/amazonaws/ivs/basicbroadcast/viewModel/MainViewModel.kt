package com.amazonaws.ivs.basicbroadcast.viewModel

import android.app.Application
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.amazonaws.ivs.basicbroadcast.R
import com.amazonaws.ivs.basicbroadcast.activities.NotificationActivity
import com.amazonaws.ivs.basicbroadcast.activities.services.BroadcastSystemCaptureService
import com.amazonaws.ivs.basicbroadcast.common.*
import com.amazonaws.ivs.basicbroadcast.common.Configuration.SLOT_CAMERA_NAME
import com.amazonaws.ivs.basicbroadcast.common.Configuration.SLOT_GAMING_NAME
import com.amazonaws.ivs.broadcast.*
import com.amazonaws.ivs.broadcast.Device.Descriptor.DeviceType
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val NOTIFICATION_CHANNEL_ID = "notificationId"
private const val NOTIFICATION_CHANNEL_NAME = "notificationName"
private const val TAG = "AmazonIVS"

class MainViewModel(private val context: Application) : ViewModel() {

    private var cameraDevice: Device.Descriptor? = null
    private var microphoneDevice: Device.Descriptor? = null
    private var attachedCameraSize: Int = 0
    private var attachedMicrophoneSize: Int = 0
    private var isMuted: Boolean = false

    var session: BroadcastSession? = null
    var paused = false

    val screenCaptureEnabled get() = captureMode.value ?: false
    private val configuration
        get() = if (screenCaptureEnabled)
            Presets.Configuration.GAMING_PORTRAIT
        else Presets.Configuration.STANDARD_PORTRAIT

    // Live data
    val preview = MutableLiveData<ImagePreviewView>()
    val clearPreview = MutableLiveData<Boolean>()
    val camerasChanged = MutableLiveData<Boolean>()
    val microphonesChanged = MutableLiveData<Boolean>()
    val indicatorColor = MutableLiveData<Int>()
    val errorHappened = MutableLiveData<Pair<String, String>>()
    val disconnectHappened = MutableLiveData<Boolean>()
    val selectDefault = MutableLiveData<DeviceType>()
    private val captureMode = MutableLiveData<Boolean>()

    private val broadcastListener by lazy {
        (object : BroadcastSession.Listener() {

            override fun onAnalyticsEvent(name: String, properties: String) {
                super.onAnalyticsEvent(name, properties)
                Log.d(TAG, "Analytics $name - $properties")
            }

            override fun onStateChanged(state: BroadcastSession.State) {
                launchMain {
                    when (state) {
                        BroadcastSession.State.CONNECTED -> {
                            Log.d(TAG, "Connected state")
                            indicatorColor.value = ContextCompat.getColor(context, R.color.colorGreen)
                        }
                        BroadcastSession.State.DISCONNECTED -> {
                            Log.d(TAG, "Disconnected state")
                            indicatorColor.value = ContextCompat.getColor(context, R.color.colorGrey)
                            launchMain { disconnectHappened.value = !paused }
                        }
                        BroadcastSession.State.CONNECTING -> {
                            Log.d(TAG, "Connecting state")
                            indicatorColor.value = ContextCompat.getColor(context, R.color.colorYellow)
                        }
                        BroadcastSession.State.ERROR -> {
                            Log.d(TAG, "Error state")
                            indicatorColor.value = ContextCompat.getColor(context, R.color.colorRed)
                        }
                        BroadcastSession.State.INVALID -> {
                            Log.d(TAG, "Invalid state")
                            indicatorColor.value = ContextCompat.getColor(context, R.color.colorOrange)
                        }
                    }
                }
            }

            override fun onAudioStats(peak: Double, rms: Double) {
                super.onAudioStats(peak, rms)
                Log.d(TAG, "Audio stats received")
            }

            override fun onDeviceRemoved(descriptor: Device.Descriptor) {
                super.onDeviceRemoved(descriptor)
                Log.d(TAG, "Device removed: ${descriptor.deviceId} - ${descriptor.type}")

                if (descriptor.deviceId == microphoneDevice?.deviceId && descriptor.isExternal()) {
                    microphoneDevice = null
                    session?.detachDevice(descriptor)
                    selectDefault.value = descriptor.type
                }

                if (descriptor.deviceId == cameraDevice?.deviceId && descriptor.isExternal()) {
                    cameraDevice = null
                    session?.detachDevice(descriptor)
                    selectDefault.value = descriptor.type
                }

                updateDeviceData()
            }

            override fun onDeviceAdded(descriptor: Device.Descriptor) {
                super.onDeviceAdded(descriptor)
                Log.d(TAG, "Device added: ${descriptor.urn} - ${descriptor.friendlyName} - ${descriptor.deviceId} - ${descriptor.position}")
                updateDeviceData()
            }

            override fun onError(error: BroadcastException) {
                Log.d(TAG, "Error is: ${error.detail} Error code: ${error.code} Error source: ${error.source}")
                if (error.error == ErrorType.ERROR_DEVICE_DISCONNECTED && error.source == microphoneDevice?.urn) {
                    microphoneDevice?.let {
                        try {
                            session?.exchangeDevices(it, it) { microphone ->
                                Log.d(TAG, "Device with id ${microphoneDevice?.deviceId} reattached")
                                microphoneDevice = microphone.descriptor
                            }
                        } catch (e: BroadcastException) {
                            Log.e(TAG, "Camera exchange exception $e")
                        }
                    }
                } else if (error.error == ErrorType.ERROR_DEVICE_DISCONNECTED && microphoneDevice == null) {
                    launchMain {
                        Toast.makeText(context, "External device ${error.source} disconnected", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    error.printStackTrace()
                    launchMain { errorHappened.value = Pair(error.code.toString(), error.detail) }
                }
            }
        })
    }

    /**
     * Create and start new session
     */
    fun createSession(onReady: () -> Unit = {}) {
        session?.release()
        BroadcastSession(context, broadcastListener, configuration, listOf(cameraDevice, microphoneDevice).toTypedArray()).apply {
            session = this
            awaitDeviceChanges {
                listAttachedDevices().run {
                    forEach { device ->
                        device?.let {
                            if (it.descriptor.type == DeviceType.CAMERA) {
                                cameraDevice = it.descriptor
                                displayCameraOutput(it)
                            }
                            if (it.descriptor.type == DeviceType.MICROPHONE) {
                                microphoneDevice = it.descriptor
                                // By default, audio devices start with a gain of 1, so we only
                                // need to change the gain on starting the session if we already know
                                // the device should be muted.
                                if (isMuted) (it as AudioDevice).setGain(0f)
                            }
                        }
                    }
                }
            }
            Log.d(TAG, "Broadcast session ready: $isReady")
            if (isReady) {
                onReady()
            } else {
                Log.d(TAG, "Broadcast session not ready")
                Toast.makeText(context, context.getString(R.string.error_create_session), Toast.LENGTH_SHORT).show()
                disconnectHappened.value = true
            }
        }
    }

    fun startSession(endpoint: String, key: String) {
        try {
            session?.start(endpoint, key)
        } catch (e: BroadcastException) {
            e.printStackTrace()
            launchMain {
                errorHappened.value = Pair(e.code.toString(), e.detail)
                disconnectHappened.value = true
            }
        }
    }

    /**
     * Updates device data in camera/microphone spinners onDeviceAdded/onDeviceRemoved
     */
    private fun updateDeviceData() {
        camerasChanged.value = attachedCameraSize != context.getAvailableCameraSize()
        attachedCameraSize = context.getAvailableCameraSize()
        microphonesChanged.value = attachedMicrophoneSize != context.getAvailableMicrophoneSize()
        attachedMicrophoneSize = context.getAvailableMicrophoneSize()
    }

    /**
     * Camera output display
     */
    private fun displayCameraOutput(device: Device) {
        try {
            (device as ImageDevice).previewView?.run {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                clearPreview.value = true
                preview.value = this
            }
        } catch (ex: BroadcastException) {
            Log.e(TAG, "Unable to display image preview")
        }
    }

    /**
     * Camera spinner on selection changed
     */
    fun cameraSelectionChanged(position: Int) {
        Log.d(TAG, "Camera device changed")
        if (position < context.getAvailableCameraSize()) {
            val device = context.getSelectedCamera(position)
            cameraDevice?.let {
                if (it.deviceId != device.deviceId && session != null) {
                    clearPreview.value = true
                    try {
                        session?.exchangeDevices(it, device) { camera ->
                            displayCameraOutput(camera)
                            cameraDevice = camera.descriptor
                        }
                    } catch (e: BroadcastException) {
                        Log.e(TAG, "Camera exchange exception $e")
                        attachCameraDevice(device)
                    }
                }
            }

            if (cameraDevice == null) attachCameraDevice(device)

            if (session == null) cameraDevice = device
        }
    }

    /**
     * Microphone spinner on selection changed
     */
    fun microphoneSelectionChanged(position: Int) {
        Log.d(TAG, "Microphone device changed")
        if (position < context.getAvailableMicrophoneSize()) {
            val device = context.getSelectedMicrophone(position)
            Log.d(TAG, "Selected device ${device.deviceId}")
            microphoneDevice?.let {
                if (it.deviceId != device.deviceId && session != null) {
                    try {
                        session?.exchangeDevices(it, device) { microphone ->
                            Log.d(TAG, "Device attached ${microphone.descriptor.deviceId}")
                            microphoneDevice = microphone.descriptor
                            if (isMuted) (microphone as AudioDevice).setGain(0f)
                        }
                    } catch (e: BroadcastException) {
                        Log.e(TAG, "Microphone exchange exception $e")
                        attachMicrophoneDevice(device)
                    }
                }
            }

            if (microphoneDevice == null) attachMicrophoneDevice(device)

            if (session == null) microphoneDevice = device
        }
    }

    /**
     * Mute attached microphones, if any.
     */
    fun mute(shouldMute: Boolean) {
        // It is important to note that when muting a microphone by adjusting the gain, the microphone will still be recording.
        // Some devices may show a physical indicator light while the microphone is active.
        // The SDK is still receiving all the real audio samples, it is just applying a gain of 0 to them. To turn off microphone
        // recording, you need to detach the microphone completely from the broadcast session, not just mute it.

        val muteAll = true // toggle to change the mute strategy. Both are functionally equivalent in this sample app

        val gain = if (shouldMute) 0f else 1f
        session?.let {
            // Wait for any ongoing device changes to complete (e.g. if the microphone selection
            // has recently changed).
            it.awaitDeviceChanges {
                // Get the list of all devices currently attached to the broadcast session.
                // Note that new audio devices start with a default gain of 1, so you would need to
                // set the gain to 0 on any newly-added audio devices in the future.
                val devices = it.listAttachedDevices()

                devices.forEach { device ->
                    if (muteAll) {
                        // For each attached device, check if it's an audio source. Here we are checking
                        // if the stream type is PCM, but you could also check if the DeviceType was MICROPHONE,
                        // USER_AUDIO, or SYSTEM_AUDIO.
                        if (device.descriptor.hasStream(Device.Descriptor.StreamType.PCM)) {
                            // Set each AudioDevice's gain.
                            (device as AudioDevice).setGain(gain)
                        }
                    } else {
                        // We only want to mute a single device. Check if this device's descriptor
                        // matches the one we would like to mute.
                        if (device.descriptor == microphoneDevice) {
                            (device as AudioDevice).setGain(gain)
                        }
                    }
                }
            }
        }
        isMuted = shouldMute
    }

    /**
     * Screen capture mode enabled
     */
    fun screenCaptureMode(screenCapture: Boolean) {
        captureMode.value = screenCapture
    }

    /**
     * Attach camera device and display output
     */
    private fun attachCameraDevice(device: Device.Descriptor) {
        session?.isReady?.let { ready ->
            if (ready) {
                if (!screenCaptureEnabled) {
                    try {
                        session?.attachDevice(device) {
                            session?.mixer?.bind(it, SLOT_CAMERA_NAME)
                            cameraDevice = it.descriptor
                            displayCameraOutput(it)
                        }
                    } catch (e: BroadcastException) {
                        Log.e(TAG, "Camera attach exception: $e")
                    }
                }
            } else {
                Log.d(TAG, "Couldn't attach camera device. Session not ready")
                Toast.makeText(context, context.getString(R.string.error_attach_device), Toast.LENGTH_SHORT).show()
                disconnectHappened.value = true
            }
        }
    }

    /**
     * Attach microphone device
     */
    private fun attachMicrophoneDevice(device: Device.Descriptor) {
        session?.isReady?.let { ready ->
            if (ready) {
                if (!screenCaptureEnabled) {
                    try {
                        session?.attachDevice(device) {
                            session?.mixer?.bind(it, SLOT_CAMERA_NAME)
                            microphoneDevice = it.descriptor
                            if (isMuted) (it as AudioDevice).setGain(0f)
                        }
                    } catch (e: BroadcastException) {
                        Log.e(TAG, "Microphone attach exception: $e")
                    }
                }
            } else {
                Log.d(TAG, "Couldn't attach microphone device. Session not ready")
                Toast.makeText(context, context.getString(R.string.error_attach_device), Toast.LENGTH_SHORT).show()
                disconnectHappened.value = true
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun createNotification(context: Context) = session?.createServiceNotificationBuilder(
        NOTIFICATION_CHANNEL_ID,
        NOTIFICATION_CHANNEL_NAME,
        Intent(context, NotificationActivity::class.java)
    )?.build()

    suspend fun startScreenCapture(data: Intent?, notification: Notification?) = suspendCoroutine {
        Log.d(TAG, "Starting screen capture: $data, $notification")
        try {
            session?.createSystemCaptureSources(data, BroadcastSystemCaptureService::class.java, notification) { devices: List<Device> ->
                Log.d(TAG, "Screen capture started")
                devices.forEach { session?.mixer?.bind(it, SLOT_GAMING_NAME) }
                it.resume(Unit)
            }
        } catch (e: BroadcastException) {
            Log.e(TAG, "System capture exception: $e")
        }
    }

    override fun onCleared() {
        super.onCleared()
        session?.release()
        session = null
    }
}

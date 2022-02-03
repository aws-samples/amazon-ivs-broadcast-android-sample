package com.amazonaws.ivs.basicbroadcast.viewModel

import android.app.Application
import android.util.Log
import android.util.Size
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.amazonaws.ivs.basicbroadcast.R
import com.amazonaws.ivs.basicbroadcast.common.*
import com.amazonaws.ivs.broadcast.*

private const val TAG = "AmazonIVS"

class CustomSourceViewModel(private val context: Application) : ViewModel() {

    var session: BroadcastSession? = null
    var paused = false

    val preview = MutableLiveData<ImagePreviewView>()
    val clearPreview = MutableLiveData<Boolean>()
    val indicatorColor = MutableLiveData<Int>()
    val errorHappened = MutableLiveData<Pair<String, String>>()
    val disconnectHappened = MutableLiveData<Boolean>()

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
                Log.d(TAG, "Audio stats received - peak ($peak), rms ($rms)")
            }

            override fun onDeviceRemoved(descriptor: Device.Descriptor) {
                super.onDeviceRemoved(descriptor)
                Log.d(TAG, "Device removed: ${descriptor.deviceId} - ${descriptor.type}")
            }

            override fun onDeviceAdded(descriptor: Device.Descriptor) {
                super.onDeviceAdded(descriptor)
                Log.d(TAG, "Device added: ${descriptor.urn} - ${descriptor.friendlyName} - ${descriptor.deviceId} - ${descriptor.position}")
            }

            override fun onError(error: BroadcastException) {
                Log.d(TAG, "Error is: ${error.detail} Error code: ${error.code} Error source: ${error.source}")
                error.printStackTrace()
                launchMain { errorHappened.value = Pair(error.code.toString(), error.detail) }
            }
        })
    }

    /**
     * Create and start new session
     */
    fun createSession(onReady: () -> Unit = {}) {
        session?.release()

        val config = BroadcastConfiguration().apply {
            // This slot will hold the custom audio and video.
            val slot = BroadcastConfiguration.Mixer.Slot.with {
                it.preferredVideoInput = Device.Descriptor.DeviceType.USER_IMAGE
                it.preferredAudioInput = Device.Descriptor.DeviceType.USER_AUDIO
                it.aspect = BroadcastConfiguration.AspectMode.FILL

                return@with it
            }

            this.mixer.slots = arrayOf(slot)

            this.video.size = BroadcastConfiguration.Vec2(720f, 1280f)
        }

        BroadcastSession(context, broadcastListener, config, null).apply {
            session = this
            Log.d(TAG, "Broadcast session ready: $isReady")
            if (isReady) {
                onReady()
            } else {
                Log.d(TAG, "Broadcast session not ready")
                Toast.makeText(context, context.getString(R.string.error_create_session), Toast.LENGTH_SHORT).show()
                return
            }
        }
    }

    fun endSession() {
        session?.stop()
        session?.release()
        session = null
    }

    /**
     * Display session's composite preview
     */
    fun displayPreview() {
        Log.d(TAG, "Displaying composite preview")
        session?.let {
            it.awaitDeviceChanges {
                it.previewView.run {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                    clearPreview.value = true
                    preview.value = this
                }
            }
        }
    }
}

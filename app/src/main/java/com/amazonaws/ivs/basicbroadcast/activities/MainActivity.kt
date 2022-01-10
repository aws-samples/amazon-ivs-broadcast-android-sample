package com.amazonaws.ivs.basicbroadcast.activities

import android.app.Activity
import android.app.Notification
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.amazonaws.ivs.basicbroadcast.App
import com.amazonaws.ivs.basicbroadcast.R
import com.amazonaws.ivs.basicbroadcast.adapters.DeviceSpinnerAdapter
import com.amazonaws.ivs.basicbroadcast.common.*
import com.amazonaws.ivs.basicbroadcast.common.Configuration.DEFAULT_DEVICE_POSITION
import com.amazonaws.ivs.basicbroadcast.data.AuthDataItem
import com.amazonaws.ivs.basicbroadcast.data.LocalCacheProvider
import com.amazonaws.ivs.basicbroadcast.databinding.ActivityMainBinding
import com.amazonaws.ivs.basicbroadcast.viewModel.MainViewModel
import com.amazonaws.ivs.broadcast.Bluetooth
import com.amazonaws.ivs.broadcast.BroadcastSession
import com.amazonaws.ivs.broadcast.Device
import com.amazonaws.ivs.broadcast.ImagePreviewView
import kotlinx.coroutines.flow.collect
import javax.inject.Inject

private const val SCREEN_CAPTURE_REQUEST_ID = 0x1000
private const val TAG = "AmazonIVS"

class MainActivity : PermissionActivity() {

    @Inject
    lateinit var cacheProvider: LocalCacheProvider

    private val viewModel: MainViewModel by lazyViewModel({ application as App }, { MainViewModel(application) })

    private var optionsVisible = true
    private var permissionsAsked = false
    private var captureStarted = false
    private var isMuted = false

    private var imagePreviewView: ImagePreviewView? = null

    private val cameraAdapter by lazy { DeviceSpinnerAdapter(this, getCameraItems()) }
    private val microphoneAdapter by lazy { DeviceSpinnerAdapter(this, getMicrophoneItems()) }

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "On Create")
        super.onCreate(savedInstanceState)
        App.component.inject(this)
        Bluetooth.startBluetoothSco(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.preview.observe(this, {
            Log.d(TAG, "Texture view changed: $it")
            binding.previewView.addView(it)
            imagePreviewView = it
        })

        viewModel.clearPreview.observe(this, { clear ->
            Log.d(TAG, "Texture view cleared")
            if (clear) binding.previewView.removeAllViews()
        })

        viewModel.indicatorColor.observe(this, { color ->
            Log.d(TAG, "Indicator color changed")
            binding.broadcastOptionView.statusIndicator.background.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        })

        viewModel.camerasChanged.observe(this, { changed ->
            if (changed) {
                Log.d(TAG, "Available camera size changed")
                cameraAdapter.clear()
                cameraAdapter.addAll(getCameraItems())
            }
        })

        viewModel.microphonesChanged.observe(this, { changed ->
            if (changed) {
                Log.d(TAG, "Available microphone size changed")
                microphoneAdapter.clear()
                microphoneAdapter.addAll(getMicrophoneItems())
            }
        })

        viewModel.errorHappened.observe(this, { error ->
            Log.d(TAG, "Error dialog is shown: ${error.first}, ${error.second}")
            showDialog(error.first, error.second)
        })

        viewModel.disconnectHappened.observe(this, { disconnected ->
            Log.d(TAG, "Disconnect happened")
            if (disconnected) {
                if (viewModel.paused) stopSession() else endSession()
            }
        })

        viewModel.selectDefault.observe(this, { type ->
            Log.d(TAG, "Default device $type changed")
            if (type == Device.Descriptor.DeviceType.MICROPHONE) {
                binding.microphoneSpinner.setSelection(DEFAULT_DEVICE_POSITION)
            } else if (type == Device.Descriptor.DeviceType.CAMERA) {
                binding.cameraSpinner.setSelection(DEFAULT_DEVICE_POSITION)
            }
        })

        initUi()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "On activity result: $requestCode, $resultCode")
        if (requestCode == SCREEN_CAPTURE_REQUEST_ID && Activity.RESULT_OK == resultCode) {
            captureStarted = true
            createSession {
                launchMain {
                    var notification: Notification? = null
                    if (Build.VERSION.SDK_INT >= 26) {
                        notification = viewModel.createNotification(applicationContext)
                    }
                    viewModel.startScreenCapture(data, notification)
                    val key = binding.optionView.edtStream.text.toString()
                    val endpoint = binding.optionView.edtEndpoint.text.toString()
                    startSession(endpoint, key)
                }
            }
            binding.optionView.root.hide()
            binding.broadcastOptionView.root.show()
        }
    }

    override fun onBackPressed() {
        stopSession()
        super.onBackPressed()
    }

    override fun onResume() {
        Log.d(TAG, "On Resume")
        super.onResume()
        if (permissionsAsked) permissionsAsked = false
    }

    override fun onDestroy() {
        Log.d(TAG, "On Destroy")
        super.onDestroy()
        endSession()
        Bluetooth.stopBluetoothSco(applicationContext)
    }

    override fun onPause() {
        Log.d(TAG, "On Pause")
        super.onPause()
        if (!viewModel.screenCaptureEnabled) {
            Log.d(TAG, "On Pause session ended")
            endSession()
        }
    }

    private fun initUi() {
        getAuthData()
        binding.version.text = BroadcastSession.getVersion()

        binding.broadcastOptionView.btnEnd.setOnClickListener {
            if (viewModel.paused) {
                endSession()
            } else {
                viewModel.session?.stop()
            }
        }

        binding.optionView.btnStart.setOnClickListener {
            hideKeyboard()
            val key = binding.optionView.edtStream.text.toString()
            val endpoint = binding.optionView.edtEndpoint.text.toString()
            if (key.isNotEmpty() && endpoint.isNotEmpty()) {
                saveAuthData(key, endpoint)
                if (!arePermissionsGranted()) {
                    askForPermissions { success ->
                        if (success) {
                            createSessionAndStart(endpoint, key)
                        }
                        permissionsAsked = success
                    }
                } else {
                    createSessionAndStart(endpoint, key)
                }
            } else {
                Toast.makeText(this, getString(R.string.error_some_fields_are_empty), Toast.LENGTH_SHORT).show()
            }
        }

        binding.mainRoot.setOnClickListener {
            val change = !optionsVisible
            binding.optionRoot.changeVisibility(change)
            binding.deviceView.changeVisibility(change)
            optionsVisible = change
        }

        binding.muteButton.setOnClickListener {
            toggleMute(!isMuted)
        }

        binding.cameraSpinner.apply {
            adapter = cameraAdapter

            onSelectionChanged { position ->
                viewModel.cameraSelectionChanged(position)
            }
        }

        binding.microphoneSpinner.apply {
            adapter = microphoneAdapter

            onSelectionChanged { position ->
                viewModel.microphoneSelectionChanged(position)
            }
        }

        binding.optionView.btnScreenCapture.apply {
            setOnClickListener {
                viewModel.screenCaptureMode(isChecked)
            }
        }
    }

    private fun createSession(onReady: () -> Unit) {
        Log.d(TAG, "Session started. Capture started: $captureStarted")
        if (viewModel.screenCaptureEnabled && !captureStarted) {
            startScreenCapture()
        } else {
            viewModel.createSession {
                onReady()
            }
            binding.optionView.root.hide()
            binding.broadcastOptionView.root.show()
        }
    }

    private fun saveAuthData(key: String, endpoint: String) {
        Log.d(TAG, "Save broadcast auth data")
        launchIO { cacheProvider.authDao().insert(AuthDataItem(key = key, endpoint = endpoint)) }
    }

    private fun getAuthData() {
        Log.d(TAG, "Collecting saved auth data")
        launchMain {
            cacheProvider.authDao().getAuth().collect { data ->
                binding.optionView.edtStream.setText(data?.key)
                binding.optionView.edtEndpoint.setText(data?.endpoint)
            }
        }
    }

    private fun startSession(endpoint: String, key: String) {
        Log.d(TAG, "Starting session")
        viewModel.session?.start(endpoint, key)
    }

    private fun createSessionAndStart(endpoint: String, key: String) {
        createSession {
            startSession(endpoint, key)
        }
    }

    private fun stopSession() {
        Log.d(TAG, "Session stopped")
        captureStarted = false
        viewModel.session?.stop()
    }

    private fun endSession() {
        Log.d(TAG, "Session ended")
        captureStarted = false
        binding.previewView.removeAllViews()
        imagePreviewView = null
        viewModel.session?.release()
        viewModel.session = null
        resetUi()
    }

    private fun resetUi() {
        binding.broadcastOptionView.root.hide()
        binding.optionView.root.show()
        toggleMute()
    }

    private fun toggleMute(shouldMute: Boolean = false) {
        if (shouldMute) {
            binding.muteButton.background = ContextCompat.getDrawable(applicationContext, R.drawable.ic_baseline_volume_off_24)
        } else {
            binding.muteButton.background = ContextCompat.getDrawable(applicationContext, R.drawable.ic_baseline_volume_up_24)
        }
        viewModel.mute(shouldMute)
        isMuted = shouldMute
    }

    private fun startScreenCapture() {
        (applicationContext.getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager)?.run {
            startActivityIfNeeded(createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_ID)
        }
    }

}

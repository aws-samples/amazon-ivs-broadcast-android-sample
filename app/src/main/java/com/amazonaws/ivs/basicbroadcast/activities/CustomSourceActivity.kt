package com.amazonaws.ivs.basicbroadcast.activities

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.amazonaws.ivs.basicbroadcast.App
import com.amazonaws.ivs.basicbroadcast.R
import com.amazonaws.ivs.basicbroadcast.common.*
import com.amazonaws.ivs.basicbroadcast.common.CameraManager
import com.amazonaws.ivs.basicbroadcast.data.AuthDataItem
import com.amazonaws.ivs.basicbroadcast.data.LocalCacheProvider
import com.amazonaws.ivs.basicbroadcast.databinding.ActivityCustomBinding
import com.amazonaws.ivs.basicbroadcast.viewModel.CustomSourceViewModel
import com.amazonaws.ivs.broadcast.AudioDevice
import com.amazonaws.ivs.broadcast.BroadcastConfiguration
import com.amazonaws.ivs.broadcast.BroadcastException
import com.amazonaws.ivs.broadcast.ImagePreviewView
import kotlinx.coroutines.flow.collect
import javax.inject.Inject

private const val TAG = "AmazonIVS"

class CustomSourceActivity : PermissionActivity() {

    @Inject
    lateinit var cacheProvider: LocalCacheProvider

    private val viewModel: CustomSourceViewModel by lazyViewModel(
        { application as App },
        { CustomSourceViewModel(application) })

    private var cameraManager: CameraManager? = null
    private var audioRecorder: AudioRecorder? = null

    private var optionsVisible = true
    private var permissionsAsked = false
    private var isKeyHidden = true

    private var imagePreviewView: ImagePreviewView? = null

    private lateinit var binding: ActivityCustomBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.component.inject(this)
        binding = ActivityCustomBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.preview.observe(this) {
            Log.d(TAG, "Texture view changed: $it")
            binding.previewView.addView(it)
            imagePreviewView = it
        }

        viewModel.clearPreview.observe(this) { clear ->
            Log.d(TAG, "Texture view cleared")
            if (clear) binding.previewView.removeAllViews()
        }

        viewModel.indicatorColor.observe(this) { color ->
            Log.d(TAG, "Indicator color changed")
            binding.broadcastOptionView.statusIndicator.background.colorFilter =
                PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }

        viewModel.errorHappened.observe(this) { error ->
            Log.d(TAG, "Error dialog is shown: ${error.first}, ${error.second}")
            showDialog(error.first, error.second)
        }

        viewModel.disconnectHappened.observe(this) {
            Log.d(TAG, "Disconnect happened")
            endSession()
        }

        initUi()
    }

    override fun onBackPressed() {
        endSession()
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        permissionsAsked = false
    }

    override fun onDestroy() {
        endSession()
        super.onDestroy()
    }

    override fun onStop() {
        Log.d(TAG, "On Stop")
        endSession()
        super.onStop()
    }

    private fun endSession() {
        Log.d(TAG, "Session ended")
        cameraManager?.release()
        audioRecorder?.release()
        binding.previewView.removeAllViews()
        imagePreviewView = null
        viewModel.endSession()
        resetUi()
    }

    private fun initUi() {
        getAuthData()

        // Screen capture not available in CustomSourceActivity.
        binding.optionView.btnScreenCapture.hide()

        binding.broadcastOptionView.btnEnd.setOnClickListener {
            endSession()
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
                            createSessionAndAttachCustomSources(endpoint, key)
                        }
                        permissionsAsked = success
                    }
                } else {
                    createSessionAndAttachCustomSources(endpoint, key)
                }
            } else {
                Toast.makeText(this, getString(R.string.error_some_fields_are_empty), Toast.LENGTH_SHORT).show()
            }
        }

        binding.optionView.btnShowhideKey.setOnClickListener {
            it as Button
            it.toggleVisibility(binding.optionView.edtStream, isKeyHidden)
            isKeyHidden = !isKeyHidden
        }

        binding.mainRoot.setOnClickListener {
            val change = !optionsVisible
            binding.optionRoot.changeVisibility(change)
            optionsVisible = change
        }
    }

    private fun resetUi() {
        binding.broadcastOptionView.root.hide()
        binding.optionView.root.show()
    }
    
    private fun createSessionAndAttachCustomSources(endpoint: String, key: String) {
        createSession {
            startSession(endpoint, key)
        }
        binding.optionView.root.hide()
        binding.broadcastOptionView.root.show()
    }

    private fun createSession(onReady: () -> Unit) {
        Log.d(TAG, "Session started")
        viewModel.createSession {
            onReady()
        }
    }

    private fun startSession(endpoint: String, key: String) {
        try {
            viewModel.session?.start(endpoint, key)
            attachCustomSources()
            viewModel.displayPreview()
        } catch (e: BroadcastException) {
            e.printStackTrace()
            launchMain {
                Log.d(TAG, "Error dialog is shown: ${e.code}, ${e.detail}")
                showDialog(e.code.toString(), e.detail)
            }
            endSession()
        }
    }

    private fun attachCustomSources() {
        Log.d(TAG, "Attaching custom sources")
        attachCustomCamera()

        // We're using a convenience method in AudioRecorder that was only introduced in Android 23.
        // Using the custom audio source is compatible back to Android 21, however. Instructions
        // on how to do so are provided in AudioRecorder.kt.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            attachCustomMicrophone()
        }
    }

    private fun attachCustomCamera() {
        CameraManager(applicationContext).apply {
            cameraManager = this
            viewModel.session?.createImageInputSource()?.let { surfaceSource ->
                this.open(surfaceSource)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun attachCustomMicrophone() {
        // Most of the logic for appending audio data from the custom microphone to the
        // broadcast session is in the AudioRecorder class, created below.
        AudioRecorder(applicationContext).apply {
            audioRecorder = this

            val sampleRate = when (this.sampleRate) {
                8000 -> BroadcastConfiguration.AudioSampleRate.RATE_8000
                16000 -> BroadcastConfiguration.AudioSampleRate.RATE_16000
                22050 -> BroadcastConfiguration.AudioSampleRate.RATE_22050
                44100 -> BroadcastConfiguration.AudioSampleRate.RATE_44100
                48000 -> BroadcastConfiguration.AudioSampleRate.RATE_48000
                else -> BroadcastConfiguration.AudioSampleRate.RATE_44100
            }

            val format = when (this.bitDepth) {
                16 -> AudioDevice.Format.INT16
                32 -> AudioDevice.Format.FLOAT32
                else -> AudioDevice.Format.INT16
            }

            // Create a AudioDevice to receive the custom audio, using the configurations determined above.
            // In this case, configuration is hardcoded in the AudioRecorder class.
            viewModel.session?.createAudioInputSource(this.channels, sampleRate, format)?.let { audioDevice ->
                // Start streaming data from the microphone to the AudioDevice.
                this.start(audioDevice)
            }
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
}

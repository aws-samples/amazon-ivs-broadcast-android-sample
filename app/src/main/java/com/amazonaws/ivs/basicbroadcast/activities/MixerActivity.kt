package com.amazonaws.ivs.basicbroadcast.activities

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.window.OnBackInvokedDispatcher
import androidx.activity.OnBackPressedCallback
import com.amazonaws.ivs.basicbroadcast.App
import com.amazonaws.ivs.basicbroadcast.R
import com.amazonaws.ivs.basicbroadcast.common.*
import com.amazonaws.ivs.basicbroadcast.databinding.ActivityMixerBinding
import com.amazonaws.ivs.basicbroadcast.viewModel.MixerViewModel
import com.amazonaws.ivs.broadcast.ImagePreviewView

private const val TAG = "AmazonIVS"

class MixerActivity : PermissionActivity() {

    private val viewModel: MixerViewModel by lazyViewModel({ this }, { MixerViewModel(application) })

    private var permissionsAsked = false

    private var imagePreviewView: ImagePreviewView? = null

    private lateinit var binding: ActivityMixerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "On Create")
        super.onCreate(savedInstanceState)
        App.component.inject(this)
        binding = ActivityMixerBinding.inflate(layoutInflater)
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

        binding.previewView.setOnClickListener {
            viewModel.swapSlots()
        }

        initBackCallback()
    }
    override fun onResume() {
        Log.d(TAG, "On Resume")
        super.onResume()
        if (!permissionsAsked) initSession()
    }

    override fun onDestroy() {
        Log.d(TAG, "On Destroy")
        endSession()
        super.onDestroy()
    }

    override fun onStop() {
        Log.d(TAG, "On Stop")
        if (permissionsAsked) permissionsAsked = false
        endSession()
        super.onStop()
    }

    private fun initSession() {
        if (!arePermissionsGranted()) {
            if (!permissionsAsked)
                askForPermissions { success ->
                    if (success) viewModel.createSession(loadLogo(), loadContentUri())
                    permissionsAsked = true
                }
        } else {
            viewModel.createSession(loadLogo(), loadContentUri())
        }
    }

    private fun endSession() {
        Log.d(TAG, "Session ended")
        binding.previewView.removeAllViews()
        imagePreviewView = null
        viewModel.endSession()
    }

    private fun loadLogo(): Bitmap {
        val options = BitmapFactory.Options()
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        return BitmapFactory.decodeResource(resources, R.drawable.ivs, options)
    }

    private fun loadContentUri(): Uri {
        return Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(applicationContext.packageName)
            .path(R.raw.ivs.toString())
            .build()
    }
    private fun backPressed() {
        endSession()
        finish()
    }

    private fun initBackCallback() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                backPressed()
            }
        })
    }
}

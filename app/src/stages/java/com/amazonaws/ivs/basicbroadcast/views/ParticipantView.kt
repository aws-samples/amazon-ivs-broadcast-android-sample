package com.amazonaws.ivs.basicbroadcast.views

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.amazonaws.ivs.broadcast.ImagePreviewView
import com.amazonaws.ivs.basicbroadcast.StageViewModel.Companion.CAMERA_SLOT_NAME
import com.amazonaws.ivs.basicbroadcast.databinding.StageParticipantViewBinding

@SuppressLint("ViewConstructor")
class ParticipantView(context: Context, participantId: String) : FrameLayout(context) {

    private val binding = StageParticipantViewBinding.inflate(LayoutInflater.from(context), this, true)
    private var preview: ImagePreviewView? = null

    init {
        if (participantId != CAMERA_SLOT_NAME) setLabel(participantId)
    }

    fun setPreview(view: ImagePreviewView?) {
        if (preview != view) {
            preview?.let { binding.root.removeView(it) }
            preview = view
            preview?.let { binding.root.addView(it) }
        }
    }

    fun setAudioMuted(muted: Boolean) {
        binding.audioMute.visibility = if (muted) View.VISIBLE else View.GONE
    }

    fun setVideoMuted(stopped: Boolean) {
        binding.cameraStop.visibility = if (stopped) View.VISIBLE else View.GONE
    }

    fun setLabel(participantId: String?) {
        with(binding.label) {
            text = participantId
            visibility = if (participantId.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }
}

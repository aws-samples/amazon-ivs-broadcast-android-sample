package com.amazonaws.ivs.realtime.basicrealtime

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import com.amazonaws.ivs.broadcast.AudioLocalStageStream
import com.amazonaws.ivs.broadcast.BroadcastException
import com.amazonaws.ivs.broadcast.Device
import com.amazonaws.ivs.broadcast.DeviceDiscovery
import com.amazonaws.ivs.broadcast.ImageLocalStageStream
import com.amazonaws.ivs.broadcast.LocalStageStream
import com.amazonaws.ivs.broadcast.ParticipantInfo
import com.amazonaws.ivs.broadcast.Stage
import com.amazonaws.ivs.broadcast.StageRenderer
import com.amazonaws.ivs.broadcast.StageStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(application: Application) : AndroidViewModel(application), Stage.Strategy, StageRenderer {

    /// If `canPublish` is `false`, the sample application will not ask for permissions or publish to the stage
    /// This will be a view-only participant.
    val canPublish = true

    // App State
    internal val participantAdapter = ParticipantAdapter()

    private val _connectionState = MutableStateFlow(Stage.ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private var publishEnabled: Boolean = false
        set(value) {
            field = value
            // Because the strategy returns the value of `checkboxPublish.isChecked`, just call `refreshStrategy`.
            stage?.refreshStrategy()
        }

    // Amazon IVS SDK resources
    private var deviceDiscovery: DeviceDiscovery? = null
    private var stage: Stage? = null
    private var streams = mutableListOf<LocalStageStream>()

    init {
        deviceDiscovery = DeviceDiscovery(application)

        if (canPublish) {
            // Create a local participant immediately to render our camera preview and microphone stats
            val localParticipant = StageParticipant(true, null)
            participantAdapter.participantJoined(localParticipant)
        }
    }

    override fun onCleared() {
        stage?.release()
        deviceDiscovery?.release()
        deviceDiscovery = null
        super.onCleared()
    }

    internal fun joinStage(token: String) {
        if (_connectionState.value != Stage.ConnectionState.DISCONNECTED) {
            // If we're already connected to a stage, leave it.
            stage?.leave()
        } else {
            if (token.isEmpty()) {
                Toast.makeText(getApplication(), "Empty Token", Toast.LENGTH_SHORT).show()
                return
            }
            try {
                // Destroy the old stage first before creating a new one.
                stage?.release()
                val stage = Stage(getApplication(), token, this)
                stage.addRenderer(this)
                stage.join()
                this.stage = stage
            } catch (e: BroadcastException) {
                Toast.makeText(getApplication(), "Failed to join stage ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            }
        }
    }

    internal fun setPublishEnabled(enabled: Boolean) {
        publishEnabled = enabled
    }

    internal fun permissionGranted() {
        val deviceDiscovery = deviceDiscovery ?: return
        streams.clear()
        val devices = deviceDiscovery.listLocalDevices()
        // Camera
        devices
            .filter { it.descriptor.type == Device.Descriptor.DeviceType.CAMERA }
            .maxByOrNull { it.descriptor.position == Device.Descriptor.Position.FRONT }
            ?.let { streams.add(ImageLocalStageStream(it)) }
        // Microphone
        devices
            .filter { it.descriptor.type == Device.Descriptor.DeviceType.MICROPHONE }
            .maxByOrNull { it.descriptor.isDefault }
            ?.let { streams.add(AudioLocalStageStream(it)) }

        stage?.refreshStrategy()

        // Update our local participant with these new streams
        participantAdapter.participantUpdated(null) {
            it.streams.clear()
            it.streams.addAll(streams)
        }
    }

    //region Stage.Strategy
    override fun stageStreamsToPublishForParticipant(
        stage: Stage,
        participantInfo: ParticipantInfo
    ): MutableList<LocalStageStream> {
        // Return the camera and microphone to be published.
        // This is only called if `shouldPublishFromParticipant` returns true.
        return streams
    }

    override fun shouldPublishFromParticipant(stage: Stage, participantInfo: ParticipantInfo): Boolean {
        return publishEnabled
    }

    override fun shouldSubscribeToParticipant(stage: Stage, participantInfo: ParticipantInfo): Stage.SubscribeType {
        // Subscribe to both audio and video for all publishing participants.
        return Stage.SubscribeType.AUDIO_VIDEO
    }
    //endregion

    //region StageRenderer
    override fun onError(exception: BroadcastException) {
        Toast.makeText(getApplication(), "onError ${exception.localizedMessage}", Toast.LENGTH_LONG).show()
        Log.e("BasicRealTime", "onError $exception")
    }

    override fun onConnectionStateChanged(
        stage: Stage,
        connectionState: Stage.ConnectionState,
        exception: BroadcastException?
    ) {
        _connectionState.value = connectionState
    }

    override fun onParticipantJoined(stage: Stage, participantInfo: ParticipantInfo) {
        if (participantInfo.isLocal) {
            // If this is the local participant joining the stage, update the participant with a null ID because we
            // manually added that participant when setting up our preview
            participantAdapter.participantUpdated(null) {
                it.participantId = participantInfo.participantId
            }
        } else {
            // If they are not local, add them normally
            participantAdapter.participantJoined(
                StageParticipant(
                    participantInfo.isLocal,
                    participantInfo.participantId
                )
            )
        }
    }

    override fun onParticipantLeft(stage: Stage, participantInfo: ParticipantInfo) {
        if (participantInfo.isLocal) {
            // If this is the local participant leaving the stage, update the ID but keep it around because
            // we want to keep the camera preview active
            participantAdapter.participantUpdated(participantInfo.participantId) {
                it.participantId = null
            }
        } else {
            // If they are not local, have them leave normally
            participantAdapter.participantLeft(participantInfo.participantId)
        }
    }

    override fun onParticipantPublishStateChanged(
        stage: Stage,
        participantInfo: ParticipantInfo,
        publishState: Stage.PublishState
    ) {
        // Update the publishing state of this participant
        participantAdapter.participantUpdated(participantInfo.participantId) {
            it.publishState = publishState
        }
    }

    override fun onParticipantSubscribeStateChanged(
        stage: Stage,
        participantInfo: ParticipantInfo,
        subscribeState: Stage.SubscribeState
    ) {
        // Update the subscribe state of this participant
        participantAdapter.participantUpdated(participantInfo.participantId) {
            it.subscribeState = subscribeState
        }
    }

    override fun onStreamsAdded(stage: Stage, participantInfo: ParticipantInfo, streams: MutableList<StageStream>) {
        // We don't want to take any action for the local participant because we track those streams locally
        if (participantInfo.isLocal) {
            return
        }
        // For remote participants, add these new streams to that participant's streams array.
        participantAdapter.participantUpdated(participantInfo.participantId) {
            it.streams.addAll(streams)
        }
    }

    override fun onStreamsRemoved(stage: Stage, participantInfo: ParticipantInfo, streams: MutableList<StageStream>) {
        // We don't want to take any action for the local participant because we track those streams locally
        if (participantInfo.isLocal) {
            return
        }
        // For remote participants, remove these streams from that participant's streams array.
        participantAdapter.participantUpdated(participantInfo.participantId) {
            it.streams.removeAll(streams)
        }
    }

    override fun onStreamsMutedChanged(
        stage: Stage,
        participantInfo: ParticipantInfo,
        streams: MutableList<StageStream>
    ) {
        // We don't want to take any action for the local participant because we track those streams locally
        if (participantInfo.isLocal) {
            return
        }
        // For remote participants, notify the adapter that the participant has been updated. There is no need to modify
        // the `streams` property on the `StageParticipant` because it is the same `StageStream` instance. Just
        // query the `isMuted` property again.
        participantAdapter.participantUpdated(participantInfo.participantId) {}
    }
    //endregion
}
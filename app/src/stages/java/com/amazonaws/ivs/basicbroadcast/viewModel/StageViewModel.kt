package com.amazonaws.ivs.basicbroadcast.viewModel

import android.app.Application
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.amazonaws.ivs.broadcast.*
import com.amazonaws.ivs.basicbroadcast.common.TAG
import com.amazonaws.ivs.basicbroadcast.common.launchMain
import com.amazonaws.ivs.basicbroadcast.common.updateSlotLayout
import com.amazonaws.ivs.basicbroadcast.models.ParticipantData
import com.amazonaws.ivs.basicbroadcast.views.ParticipantView
import kotlin.collections.set

@RequiresApi(Build.VERSION_CODES.Q)
class StageViewModel(private val context: Application) : ViewModel() {

    private var stage: Stage? = null
    private var broadcastSession: BroadcastSession? = null
    private var stageToken: String? = null

    private val deviceDiscovery = DeviceDiscovery(context)
    private var imageStream: ImageLocalStageStream? = null
    private var microphoneStream: AudioLocalStageStream? = null

    // State tracking
    val joined: Boolean get() = joinHappened.value == true
    val broadcasting: Boolean get() = broadcastHappened.value == true
    var participantIds = ArrayList<String>()
    var shouldPublish: Boolean = true

    // Mapping of participant ID to stream
    private val remoteAudioStreamMap = HashMap<String, AudioStageStream>()
    private val remoteVideoStreamMap = HashMap<String, ImageStageStream>()
    private val broadcastSlotMap = HashMap<String, BroadcastConfiguration.Mixer.Slot>()
    private val participantMap = HashMap<String, ParticipantData>()
    val participantViewMap = HashMap<String, ParticipantView>()

    // LiveData
    val viewToAdd = NoLossEvent<ParticipantView>()
    val viewToRemove = NoLossEvent<ParticipantView>()
    val localViewToAdd = NoLossEvent<ParticipantView>()
    val removeAllViews = NoLossEvent<Boolean>()
    val removeAllParticipants = NoLossEvent<Boolean>()
    val joinHappened = MutableLiveData<Boolean>()
    var broadcastHappened = MutableLiveData<Boolean>()

    companion object {
        const val CAMERA_SLOT_NAME = "default"
    }

    /**
     * Participant device audio stats callback
     */
    private class AudioStatsCallback(private var id: String, private var deviceId: String) : AudioDevice.StatsCallback {
        override fun op(peak: Float, rms: Float) {
            Log.i(TAG, "Audio stats for $id with device $deviceId: peak=$peak, rms=$rms")
        }
    }

    /**
     * Participant stream listener
     */
    private class StreamListener(private val id: String, private val streamType: StageStream.Type) : StageStream.Listener {
        override fun onMutedChanged(isMuted: Boolean) {
            Log.i(TAG, "onMutedChanged: Id - $id Type - $streamType muted - $isMuted")
        }

        override fun onRTCStats(statsMap: MutableMap<String, MutableMap<String, String>>) {
            Log.i(TAG, "onRTCStats: Id - $id Type - $streamType stats - $statsMap")
        }

        override fun onLocalAudioStats(stats: LocalAudioStats) {
            Log.i(TAG, "LocalAudioStats: networkQuality=${stats.networkQuality}")
        }

        override fun onLocalVideoStats(stats: MutableList<LocalVideoStats>) {
            stats.forEach { stat ->
                Log.i(TAG, "LocalVideoStats: networkQuality=${stat.networkQuality}")
            }
        }

        override fun onRemoteAudioStats(stats: RemoteAudioStats) {
            Log.i(TAG, "RemoteAudioStats: networkQuality=${stats.networkQuality}")
        }

        override fun onRemoteVideoStats(stats: RemoteVideoStats) {
            Log.i(TAG, "RemoteVideoStats: networkQuality=${stats.networkQuality}")
        }
    }

    /**
     * Stage renderer
     */
    private val stageRenderer by lazy {
        object : AnalyticsStageRenderer {
            /**
             * Callback received when stage error occurs
             */
            override fun onError(error: BroadcastException) {
                with(error) {
                    Log.d(TAG, "onError: $detail Error code: $code Error source: $source")
                    launchMain {
                        Toast.makeText(context, "Stage error: $code - $detail", Toast.LENGTH_SHORT).show()
                    }
                    printStackTrace()
                }
            }

            /**
             * Callback received when session connection state has changed
             */
            override fun onConnectionStateChanged(stage: Stage, state: Stage.ConnectionState, exception: BroadcastException?) {
                exception?.let { onError(it) }
                Log.d(TAG, "onConnectionStateChanged: $state")
                when (state) {
                    Stage.ConnectionState.DISCONNECTED -> {
                        clearData()
                        leaveStage()
                        joinHappened.setValue(false)
                    }
                    Stage.ConnectionState.CONNECTED -> {
                        joinHappened.setValue(true)
                    }
                    else -> {
                        // not used
                    }
                }

                if (state == Stage.ConnectionState.DISCONNECTED) launchMain { leaveStage() }
            }

            /**
             * Callback received when participant joins the session
             */
            override fun onParticipantJoined(stage: Stage, participantInfo: ParticipantInfo) {
                with(participantInfo) {
                    Log.d(TAG, "onParticipantJoined: $participantId")
                    participantMap[participantId] = ParticipantData(
                        strategy.shouldSubscribeToParticipant(stage, this),
                        audioMuted = false,
                        videoMuted = false,
                        isPublishing = false,
                        info = this
                    )
                }
            }

            /**
             * Callback received when participant leaves the session
             */
            override fun onParticipantLeft(stage: Stage, participantInfo: ParticipantInfo) {
                with(participantInfo) {
                    Log.d(TAG, "onParticipantLeft: $participantId")
                    participantMap.remove(participantId)
                }
            }

            /**
             * Callback received when participant publishing state has changed
             */
            override fun onParticipantPublishStateChanged(stage: Stage, participantInfo: ParticipantInfo, state: Stage.PublishState) {
                Log.d(TAG, "onParticipantPublishStateChanged: ${participantInfo.participantId} State - $state")
                with(participantInfo) {
                    participantMap[participantId]?.apply {
                        subscribeType = strategy.shouldSubscribeToParticipant(stage, this@with)
                        isPublishing = state == Stage.PublishState.PUBLISHED
                        info = this@with
                    }

                    if (isLocal) {
                        participantViewMap[CAMERA_SLOT_NAME]?.setLabel("You ($participantId)")
                    }
                    else {
                        participantViewMap[participantId]?.setLabel(participantId)
                    }
                }
            }

            override fun onParticipantSubscribeStateChanged(stage: Stage, participantInfo: ParticipantInfo, state: Stage.SubscribeState) {
                Log.d(TAG, "onParticipantSubscribeStateChanged: ${participantInfo.participantId} State - $state")
            }

            /**
             * Callback received when participant streams are added to the session
             */
            override fun onStreamsAdded(stage: Stage, participantInfo: ParticipantInfo, streams: MutableList<StageStream>) {
                streams.forEach { remoteStream ->
                    Log.d(TAG, "onStreamsAdded: ${participantInfo.participantId} Local: ${participantInfo.isLocal}")
                    if (!participantInfo.isLocal) {
                        launchMain { addParticipantStream(participantInfo.participantId, remoteStream) }
                    }
                    remoteStream.setListener(StreamListener(participantInfo.participantId, remoteStream.streamType))
                }
                updateMutedState(participantInfo.participantId)
                updateSlotLayout()
            }

            /**
             * Callback received when participant streams are removed from to the session
             */
            override fun onStreamsRemoved(stage: Stage, participantInfo: ParticipantInfo, streams: MutableList<StageStream>) {
                Log.d(TAG, "onStreamsRemoved: ${participantInfo.participantId} Streams added - ${streams.size}")
                streams.forEach { _ ->
                    if (!participantInfo.isLocal) {
                        launchMain { removeParticipant(participantInfo.participantId) }
                    }
                }
                updateMutedState(participantInfo.participantId)
                updateSlotLayout()
            }

            /**
             * Callback received when participant stream audio state has changes
             */
            override fun onStreamsMutedChanged(stage: Stage, participantInfo: ParticipantInfo, streams: MutableList<StageStream>) {
                participantInfo.participantId.let { id ->
                    Log.d(TAG, "onStreamMutedChanged: $id Streams added - ${streams.size}")
                    streams.forEach { remoteStream ->
                        participantMap[id]?.let { participant ->
                            if (remoteStream.streamType == StageStream.Type.AUDIO) {
                                participant.audioMuted = remoteStream.muted
                            } else if (remoteStream.streamType == StageStream.Type.VIDEO) {
                                participant.videoMuted = remoteStream.muted
                            }
                        }
                    }
                    updateMutedState(participantInfo.participantId)
                    updateSlotLayout()
                }
            }

            /**
             * Callback for analytics properties
             */
            override fun onAnalyticsEvent(name: String, properties: String) {
                Log.d(TAG, "onAnalyticsEvent: $name - $properties")
            }
        }
    }

    private val broadcastListener: BroadcastSession.Listener by lazy {
        (object : BroadcastSession.Listener() {

            /**
             * Callback received when broadcasting state has changed
             */
            override fun onStateChanged(state: BroadcastSession.State) {
                Log.d(TAG, "onStateChanged: $state")
                when (state) {
                    BroadcastSession.State.CONNECTED -> {
                        broadcastHappened.postValue(true)
                    }
                    BroadcastSession.State.DISCONNECTED -> {
                        broadcastHappened.postValue(false)
                    }
                    BroadcastSession.State.CONNECTING,
                    BroadcastSession.State.ERROR,
                    BroadcastSession.State.INVALID -> {
                        // not used
                    }
                }
            }

            /**
             * Callback received when broadcast error occurs
             */
            override fun onError(error: BroadcastException) {
                with(error) {
                    Log.d(TAG, "onError: $detail Error code: $code Error source: $source")
                    launchMain {
                        Toast.makeText(context, "Broadcast error: $code - $detail", Toast.LENGTH_SHORT).show()
                    }
                    printStackTrace()
                }
            }
        })
    }

    /**
     * Stage strategy callback which is used to update participant publish and subscribe info
     * Call 'Stage.refreshStrategy' to update
     */
    private val strategy = object : Stage.Strategy {
        override fun stageStreamsToPublishForParticipant(stage: Stage, participantInfo: ParticipantInfo): List<LocalStageStream> {
            assert(participantInfo.isLocal)
            with(ArrayList<LocalStageStream>()) {
                imageStream?.let { add(it) }
                microphoneStream?.let { add(it) }
                return this
            }
        }

        override fun shouldPublishFromParticipant(stage: Stage, participantInfo: ParticipantInfo): Boolean {
            return shouldPublish
        }

        override fun shouldSubscribeToParticipant(stage: Stage, participantInfo: ParticipantInfo): Stage.SubscribeType {
            return participantMap[participantInfo.participantId]?.subscribeType ?: Stage.SubscribeType.AUDIO_VIDEO
        }
    }

    fun needCreateStage(token: String): Boolean = stage == null || stageToken != token

    fun createStage(token: String, callback: (() -> Unit)? = null) {
        releaseSession()
        stageToken = token
        try {
            Stage(context, token, strategy).apply {
                Log.d(TAG, "Stage session created")
                stage = this
                addRenderer(stageRenderer)
                callback?.invoke()
            }
        } catch (e: BroadcastException) {
            Log.e(TAG, "Exception happened when creating stage session: $e")
            Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    fun attachImageStream(muted: Boolean = false) {
        try {
            deviceDiscovery.listLocalDevices()[(getDefaultCameraPosition())].let { device ->
                imageStream = ImageLocalStageStream(device, null).also { stream ->
                    stream.muted = muted
                }
                broadcastSession?.attachDevice(device.descriptor) {
                    Log.d(TAG, "Camera device ${it.descriptor.deviceId} attached")
                    // Call `refreshStrategy` to refresh local video stream state
                    stage?.refreshStrategy()
                    broadcastSession?.mixer?.bind(it, CAMERA_SLOT_NAME)
                }

                imageStream?.preview?.let { preview ->
                    addLocalPreview(preview)
                }
            }
        } catch (e: BroadcastException) {
            Log.e(TAG, "Image stream attach exception: $e")
        }
    }

    fun attachMicrophoneStream() {
        deviceDiscovery.listLocalDevices().find { device -> device.descriptor.type == Device.Descriptor.DeviceType.MICROPHONE }?.let { device ->
            microphoneStream = AudioLocalStageStream(device).apply {
                setStatsCallback(AudioStatsCallback(CAMERA_SLOT_NAME, device.descriptor.deviceId))
                try {
                    broadcastSession?.attachDevice(device.descriptor) {
                        Log.d(TAG, "Microphone device ${it.descriptor.deviceId} attached")
                        // Call `refreshStrategy` to refresh local audio stream state
                        stage?.refreshStrategy()
                    }
                }  catch (e: BroadcastException) {
                    Log.e(TAG, "Microphone attach exception: $e")
                }
            }
        }
    }

    private fun detachImageStream() {
        Log.d(TAG,"Detaching image stream")
        imageStream?.let {
            broadcastSession?.detachDevice(it.device)
            imageStream = null
            // Call `refreshStrategy` to refresh local video stream state
            stage?.refreshStrategy()
        }
    }

    /**
     * Find participant device in available participant source list and attach audio stats callback
     * Add participant slot to mixer (broadcasting) and display participant preview
     */
    private fun addParticipantStream(participantId: String, remoteStream: StageStream) {
        if (!broadcastSlotMap.contains(participantId)) {
            broadcastSlotMap[participantId] = createMixerSlot(participantId)
        }
        associateAudioToParticipant(participantId, remoteStream)
        associateVideoToParticipant(participantId, remoteStream)
    }

    private fun associateAudioToParticipant(participantId: String, remoteStream: StageStream) {
        if (remoteStream.streamType == StageStream.Type.AUDIO) {
            participantMap[participantId]?.audioMuted = remoteStream.muted
            remoteAudioStreamMap[participantId] = remoteStream as AudioStageStream
            remoteStream.setStatsCallback(AudioStatsCallback(participantId, remoteStream.device.descriptor.deviceId))
        }
    }

    private fun associateVideoToParticipant(participantId: String, remoteStream: StageStream) {
        if (remoteStream.streamType == StageStream.Type.VIDEO) {
            try {
                val preview = remoteStream.preview
                participantMap[participantId]?.videoMuted = remoteStream.muted
                remoteVideoStreamMap[participantId] = remoteStream as ImageStageStream
                addParticipantWithPreview(participantId, remoteStream.preview)
            } catch (e: BroadcastException) {
                Log.d(TAG, "Unable to get preview: $e")
            }
        }
    }

    /**
     * Create mixer slot with default configuration
     */
    private fun createMixerSlot(slotName: String): BroadcastConfiguration.Mixer.Slot {
        return BroadcastConfiguration.Mixer.Slot.with { config ->
            config.apply {
                name = slotName
                size = BroadcastConfiguration.Vec2(720f, 1280f)
                aspect = BroadcastConfiguration.AspectMode.FIT
                position = BroadcastConfiguration.Vec2(0f, 0f)
                setzIndex(0)
                return@with config
            }
        }
    }
    private fun getParticipantView(participantId: String) = ParticipantView(context, participantId).apply {
        Log.d(TAG, "Participant $participantId preview is created")
        participantMap[participantId]?.apply {
            setAudioMuted(audioMuted)
            setVideoMuted(videoMuted)
        }
    }

    private fun updateMutedState(participantId: String) {
        participantViewMap[participantId]?.let { view ->
            participantMap[participantId]?.apply {
                view.setAudioMuted(audioMuted)
                view.setVideoMuted(videoMuted)
            }
        }
    }


    private fun addParticipantWithPreview(participantId: String, preview: ImagePreviewView) {
        with(participantIds) {
            if (contains(participantId)) participantViewMap.remove(participantId)?.let { viewToRemove.setValue(it) }
            else add(participantId)
        }
        with(getParticipantView(participantId)) {
            participantViewMap[participantId] = this
            viewToAdd.setValue(this)
            setPreview(preview)
        }
        updateSlotLayout()
        Log.d(TAG, "Participant $participantId camera feed is added")
    }

    private fun addLocalPreview(preview: ImagePreviewView) {
        with(participantIds) { if (!contains(CAMERA_SLOT_NAME)) add(CAMERA_SLOT_NAME) }
        with(getParticipantView(CAMERA_SLOT_NAME)) {
            participantViewMap[CAMERA_SLOT_NAME] = this
            localViewToAdd.setValue(this)
            setPreview(preview)
        }
        updateSlotLayout()
        Log.d(TAG, "Local camera feed is added")
    }

    private fun removeParticipant(participantId: String) {
        participantIds.remove(participantId)
        participantViewMap.remove(participantId)?.let {
            Log.d(TAG, "Participant $participantId camera feed is removed")
            viewToRemove.setValue(it)
        }
        updateSlotLayout()
    }

    /**
     * Update participant mixer slot positions (broadcasting)
     */
    private fun updateSlotLayout() {
        broadcastSession?.mixer?.slots?.let { slots ->
            slots.forEach { slot ->
                with(slot) {
                    if (!participantIds.contains(name)) {
                        broadcastSession?.run {
                            mixer?.removeSlot(name)
                            remoteAudioStreamMap[name]?.let { detachDevice(it.device) }
                            remoteVideoStreamMap[name]?.let { detachDevice(it.device) }
                        }
                        broadcastSlotMap.remove(name)
                        remoteAudioStreamMap.remove(name)
                        remoteVideoStreamMap.remove(name)
                    }
                }
            }

            broadcastSlotMap.forEach { entry ->
                // Add any necessary slots
                slots.firstOrNull { it.name == entry.value.name }.let { slot ->
                    if (slot == null) {
                        try {
                            broadcastSession?.run {
                                mixer?.addSlot(entry.value)

                                remoteAudioStreamMap[entry.key]?.let { audioStream ->
                                    attachDevice(audioStream.device)
                                    mixer?.bind(audioStream.device, entry.key)
                                }
                                remoteVideoStreamMap[entry.key]?.let { videoStream ->
                                    attachDevice(videoStream.device)
                                    mixer?.bind(videoStream.device, entry.key)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Exception while updating broadcast slot layout $e")
                            e.printStackTrace()
                        }
                    } else {
                        // Add any necessary streams to existing slot
                        // Removing streams is handled in removeParticipant
                        val streamsNeedToExist = ArrayList<StageStream>().apply {
                            // Audio
                            remoteAudioStreamMap[entry.key]?.let { add(it) }
                            // Video
                            remoteVideoStreamMap[entry.key]?.let { add(it) }
                        }

                        for (stream in streamsNeedToExist) {
                            val device = stream.device
                            if (broadcastSession?.listAttachedDevices()
                                    ?.find { it.descriptor.deviceId == device.descriptor.deviceId && it.descriptor.type == device.descriptor.type } == null
                            ) {
                                try {
                                    // Need to add
                                    broadcastSession?.attachDevice(device)
                                    broadcastSession?.mixer?.bind(device, entry.key)
                                } catch (e: BroadcastException) {
                                    Log.e(TAG, "Device attach exception: $e")
                                }
                            }
                        }
                    }
                }
            }
            participantIds.filter { it == CAMERA_SLOT_NAME || remoteVideoStreamMap[it] != null }.let { list ->
                broadcastSession?.updateSlotLayout(list)
            }
        }
    }

    /**
     * Release stage and broadcast session
     */
    private fun releaseSession() {
        stage?.release()
        stage = null
        clearData()
    }

    /**
     * Clear stored connected participant data
     */
    private fun clearData() {
        removeAllParticipants.setValue(true)
        participantIds.removeAll { it != CAMERA_SLOT_NAME }
        participantViewMap[CAMERA_SLOT_NAME]?.setLabel("")
        with(participantViewMap) {
            keys.filter { it != CAMERA_SLOT_NAME }.forEach { id ->
                this[id]?.let { viewToRemove.setValue(it) }
                remove(id)
            }
        }
        participantMap.clear()
    }

    /**
     * Calculates the default (FRONT) camera device position
     */
    private fun getDefaultCameraPosition(): Int {
        with(deviceDiscovery) {
            hasAvailableFrontCamera().let { device ->
                if (device != null) return listLocalDevices().map { it.descriptor.deviceId }.indexOf(device.descriptor.deviceId)
                else return 0
            }
        }
    }

    private fun hasAvailableFrontCamera(): Device? =
        deviceDiscovery.listLocalDevices().find { device ->
            device.descriptor.type == Device.Descriptor.DeviceType.CAMERA && device.descriptor.position == Device.Descriptor.Position.FRONT
        }

    private fun publish() {
        Log.d(TAG, "Stage published")
        stage?.let { stage ->
            shouldPublish = true
            // Call `refreshStrategy` to refresh publishing state
            stage.refreshStrategy()
        }
    }

    private fun unpublish() {
        Log.d(TAG, "Stage unpublished")
        stage?.let { stage ->
            shouldPublish = false
            // Call `refreshStrategy` to refresh publishing state
            stage.refreshStrategy()
        }
    }

    private fun changeSubscribeType(type: Stage.SubscribeType) {
        Log.d(TAG, "Participant subscribe type changed to $type")
        participantMap.values.map { data ->
            data.subscribeType = type
        }
        // Call `refreshStrategy` to trigger participant subscribe type refresh
        stage?.refreshStrategy()
    }

    fun startBroadcast(key: String, endpoint: String) {
        broadcastSession?.release()
        Log.d(TAG, "Creating and starting broadcast session: $endpoint, $key")
        val startingDevices: ArrayList<Device.Descriptor> = ArrayList<Device.Descriptor>().apply {
            imageStream?.let { add(it.device.descriptor) }
            microphoneStream?.let { add(it.device.descriptor) }
        }

        BroadcastSession(context, broadcastListener, BroadcastConfiguration(), startingDevices.toTypedArray()).let { session ->

            session.mixer.run {
                imageStream?.device?.let { bind(it, CAMERA_SLOT_NAME) }
                microphoneStream?.device?.let { bind(it, CAMERA_SLOT_NAME) }
            }

            broadcastSession = session
            updateSlotLayout()

            if (session.isReady) {
                try {
                    Log.d(TAG, "Stage broadcast started")
                    session.start(endpoint, key)
                } catch (e: BroadcastException) {
                    Log.e(TAG, "Exception while starting broadcast $e")
                }
            }
        }
    }

    fun stopBroadcast() {
        if (broadcasting) {
            broadcastSession?.stop()
            Log.d(TAG, "Broadcast session stopped")
        }
    }

    fun joinStage() {
        try {
            stage?.join()?.let {
                Log.d(TAG, "Stage join")
            }
        } catch (e: BroadcastException) {
            Log.e(TAG, "Exception while joining stage $e")
        }
    }

    fun leaveStage() {
        removeAllParticipants.setValue(true)
        participantViewMap[CAMERA_SLOT_NAME]?.setLabel("")
        if (joined) {
            stage?.leave()
            Log.d(TAG, "Stage leave")
        }
        clearData()
    }

    fun unmuteImageStream() {
        Log.d(TAG, "Local image stream is unmuted")
        imageStream?.muted = false
    }

    fun muteImageStream() {
        Log.d(TAG, "Local image stream is muted")
        imageStream?.muted = true
    }

    fun unmuteMicrophoneStream() {
        Log.d(TAG, "Local microphone stream is unmuted")
        microphoneStream?.muted = false
    }

    fun muteMicrophoneStream() {
        Log.d(TAG, "Local microphone stream is muted")
        microphoneStream?.muted = true
    }

    fun release() {
        if (joined) leaveStage()
        if (broadcasting) stopBroadcast()

        releaseSession()

        viewToAdd.clearObserver()
        viewToRemove.clearObserver()
        localViewToAdd.clearObserver()
        removeAllViews.clearObserver()
        removeAllParticipants.clearObserver()
    }

    fun enterBackground() {
        changeSubscribeType(Stage.SubscribeType.AUDIO_ONLY)
        detachImageStream()
        unpublish()
    }

    fun enterForeground(cameraMuted: Boolean) {
        changeSubscribeType(Stage.SubscribeType.AUDIO_VIDEO)
        attachImageStream(cameraMuted)
        publish()
    }

}

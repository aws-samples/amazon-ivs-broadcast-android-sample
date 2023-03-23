package com.amazonaws.ivs.basicbroadcast.models

import com.amazonaws.ivs.broadcast.ParticipantInfo
import com.amazonaws.ivs.broadcast.Stage.SubscribeType

data class ParticipantData(
    var subscribeType: SubscribeType,
    var audioMuted: Boolean,
    var videoMuted: Boolean,
    var isPublishing: Boolean,
    var info: ParticipantInfo
)

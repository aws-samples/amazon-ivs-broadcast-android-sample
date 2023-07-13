package com.amazonaws.ivs.realtime.basicrealtime

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class ParticipantAdapter : RecyclerView.Adapter<ParticipantAdapter.ViewHolder>() {

    private val participants = mutableListOf<StageParticipant>()

    init {
        setHasStableIds(true)
    }

    fun participantJoined(participant: StageParticipant) {
        participants.add(participant)
        notifyItemInserted(participants.size - 1)
    }

    fun participantLeft(participantId: String) {
        val index = participants.indexOfFirst { it.participantId == participantId }
        if (index != -1) {
            participants.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    fun participantUpdated(participantId: String?, update: (participant: StageParticipant) -> Unit) {
        val index = participants.indexOfFirst { it.participantId == participantId }
        if (index != -1) {
            update(participants[index])
            notifyItemChanged(index, participants[index])
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val item = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stage_participant, parent, false) as ParticipantItem
        return ViewHolder(item)
    }

    override fun getItemCount(): Int {
        return participants.size
    }

    override fun getItemId(position: Int): Long =
        participants[position]
            .stableID
            .hashCode()
            .toLong()

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        return holder.participantItem.bind(participants[position])
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        val updates = payloads.filterIsInstance<StageParticipant>()
        if (updates.isNotEmpty()) {
            updates.forEach { holder.participantItem.bind(it) }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    class ViewHolder(val participantItem: ParticipantItem) : RecyclerView.ViewHolder(participantItem)
}
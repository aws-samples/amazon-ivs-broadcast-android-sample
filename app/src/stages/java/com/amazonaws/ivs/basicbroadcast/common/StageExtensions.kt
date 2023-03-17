package com.amazonaws.ivs.basicbroadcast.common

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.amazonaws.ivs.basicbroadcast.R
import com.amazonaws.ivs.broadcast.BroadcastConfiguration
import com.amazonaws.ivs.broadcast.BroadcastSession
import com.amazonaws.ivs.basicbroadcast.views.ParticipantView
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

private var videoSize: BroadcastConfiguration.Vec2 = BroadcastConfiguration.Vec2(720f, 1280f)

private const val STAGE_PREFERENCES = "StagePreferences"
private const val TOKEN_KEY = "StageTokenKey"
private const val ENDPOINT_KEY = "StageEndpointKey"
private const val STREAM_KEY = "StageStreamKey"

/**
 * Update participant previews in GridLayout
 */
fun GridLayout.updateLayout(participantIds: ArrayList<String>, participantViewMap: HashMap<String, ParticipantView>) {
    try {
        participantIds.size.let { size ->
            if (participantIds.isEmpty()) return
            val numRows = ceil(sqrt(size.toDouble())).toInt()
            val numColumns = calculateColumns(size)

            if (gridSizeIncreased(numRows, numColumns)) {
                columnCount = numColumns
                rowCount = numRows
            }

            var index = 0
            for (row in 0 until numRows) {
                for (column in 0 until numColumns) {
                    if (index >= size) return
                    participantViewMap[participantIds[index]]?.let { view ->
                        ((view.layoutParams ?: GridLayout.LayoutParams()) as GridLayout.LayoutParams).apply {
                            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                            rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                            height = this@updateLayout.height / numRows
                            width = this@updateLayout.width / numColumns
                            view.layoutParams = this
                        }
                    }
                    index += 1
                }
            }
            if (columnCount.sizeChanged(numColumns)) columnCount = numColumns
            if (rowCount.sizeChanged(numRows)) rowCount = numRows
            invalidate()
        }
    } catch (e: Exception) {
        Log.e(TAG, "updateLayout: Layout update exception $e")
    }
}

/**
 * Update participant mixer slot positions (broadcasting)
 */
fun BroadcastSession.updateSlotLayout(participantIds: List<String>) {
    participantIds.size.let { size ->
        val numRows = ceil(sqrt(size.toDouble())).toInt()
        val numColumns = calculateColumns(size)

        val height = videoSize.y / numRows
        val width = videoSize.x / numColumns
        var index = 0
        for (row in 0 until numRows) {
            for (column in 0 until numColumns) {
                if (index >= size) return
                participantIds[index].let { participantId ->
                    mixer?.slots?.first { it.name == participantId }?.let { participantSlot ->
                        participantSlot.changing { s ->
                            s.name = participantId
                            s.size = BroadcastConfiguration.Vec2(width, height)
                            s.position = BroadcastConfiguration.Vec2(column * width, row * height)
                            s.aspect = BroadcastConfiguration.AspectMode.FIT
                            s
                        }.apply {
                            mixer?.transition(participantId, this, 1.0, null)
                            index += 1
                        }
                    }
                }
            }
        }
    }
}

private fun GridLayout.gridSizeIncreased(numRows: Int, numColumns: Int): Boolean = rowCount <= numRows && columnCount <= numColumns

private fun Int.sizeChanged(size: Int) = this != size && this >= size

/**
 * Calculates columns for GridLayout
 */
private fun calculateColumns(numParticipants: Int): Int =
    with(sqrt(numParticipants.toDouble())) {
        if (numParticipants > 2) if (ceil(numParticipants.toDouble() / 3).toInt().isEqualValue()) floor(this).toInt() else ceil(this).toInt() else 1
    }

fun Context.saveToken(token: String) {
    saveToSharedPreferences(this, TOKEN_KEY, token)
}

fun Context.saveBroadcastData(streamKey: String, endpoint: String) {
    saveToSharedPreferences(this, STREAM_KEY, streamKey)
    saveToSharedPreferences(this, ENDPOINT_KEY, endpoint)
}

fun Context.getToken(): String = getFromSharedPreferences(this, TOKEN_KEY)

fun Context.getEndpoint(): String = getFromSharedPreferences(this, ENDPOINT_KEY)

fun Context.getStreamKey(): String = getFromSharedPreferences(this, STREAM_KEY)

fun View.toggleVisibility() {
    visibility = if (visibility == View.GONE) View.VISIBLE else View.GONE
}

fun TextView.setBroadcastingTextColor(broadcasting: Boolean) {
    setTextColor(ContextCompat.getColorStateList(context, if (broadcasting) R.color.green else R.color.white))
}

fun Int.isEqualValue() = (this % 2) == 0

private fun saveToSharedPreferences(context: Context, key: String, value: String) {
    context.getSharedPreferences(STAGE_PREFERENCES, 0).let {
        it.edit().apply {
            putString(key, value)
            apply()
        }
    }
}

private fun getFromSharedPreferences(context: Context, key: String): String =
    context.getSharedPreferences(STAGE_PREFERENCES, 0).getString(key, "") ?: ""

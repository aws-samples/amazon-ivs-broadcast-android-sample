package com.amazonaws.ivs.basicbroadcast.common

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.RequiresApi
import com.amazonaws.ivs.broadcast.AudioDevice
import java.nio.ByteBuffer

/**
 * This class streams from the default system microphone to the custom audio source provided by the
 * broadcast session.
 *
 * Note that for lowest latency microphone audio, it is best to use the preset microphone from the broadcast
 * SDK, which uses AAudio or OpenSL. This class is intended to demonstrate the use of the custom audio source,
 * but is not optimized for production.
 */
class AudioRecorder(val context: Context) {
    private val handlerThread: HandlerThread
    private val handler: Handler
    
    private var audioRecord: AudioRecord? = null
    private var bufferSize: Int = 0
    private var device: AudioDevice? = null
    private var buffer: ByteBuffer
    private var pts: Double = 0.0

    // 44100Hz 16-bit mono. You may need to change these values for your own use case.
    val sampleRate = 44100
    val channels = 1
    val bitDepth = 16

    private var isRecording: Boolean = false

    init {
        HandlerThread("com.amazonaws.ivs.basicbroadcast.AudioRecorder").apply {
            handlerThread = this
            this.start()
            handler = Handler(this.looper)
        }

        val configuration = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        // Double the minimum buffer size. You may optimize bufferSize differently for your application.
        bufferSize =
            AudioRecord.getMinBufferSize(sampleRate, configuration, encoding) * 2
        buffer = ByteBuffer.allocateDirect(bufferSize)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                configuration,
                encoding,
                bufferSize
            )
        } catch (exception: SecurityException) {
            Log.e(TAG, "Cannot record without permission")
        }
    }

    @Synchronized
    @RequiresApi(Build.VERSION_CODES.M)
    fun start(device: AudioDevice) {
        if (isRecording) {
            return
        }
        isRecording = true
        pts = 0.0
        this.device = device
        handler.post {
            audioRecord?.startRecording()
            record()
        }
    }

    @Synchronized
    fun release() {
        stopRecording()
        handlerThread.quitSafely()
    }

    @Synchronized
    private fun stopRecording() {
        if (!isRecording) {
            return
        }
        handler.removeCallbacksAndMessages(null)
        isRecording = false
        audioRecord?.stop()
    }

    /**
     * This method requires Android version 23+ because the AudioRecord.record(...) API only
     * started supporting ByteBuffer at this time.
     *
     * To support earlier versions of Android, it is necessary to use a different buffer type (e.g.
     * short[]) with AudioRecord.read(...) and then convert that to a Direct ByteBuffer in order to
     * append to the custom audio source.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun record() {
        if (isRecording) {
            audioRecord?.let {
                // Read microphone data from AudioRecord. It is also possible to use the custom
                // audio source with audio read from a file or other input (for example, to play a sound
                // when a user presses a button).

                // Here we are using a blocking read. For lowest latency, you may want to use
                // a non-blocking read and schedule polling time differently.
                val bytesRead = it.read(buffer, bufferSize, AudioRecord.READ_BLOCKING)

                // Other audio processing can happen here as needed.

                // Error check.
                if (bytesRead < 0) {
                    Log.e("AmazonIVS", "Error reading from microphone")
                    stopRecording()
                    return
                } else if (bytesRead > 0) { // while microphone is on, this will always be greater than 0 since we are using a blocking read
                    val samples = bytesRead / channels / (bitDepth / 8)

                    // Append the buffer to the custom AudioDevice in the broadcast session.
                    // Note that appending to the AudioDevice must happen on the same thread in which the
                    // broadcast session was first created.
                    launchMain {
                        device?.let { device ->

                            if (!isRecording) {
                                return@launchMain
                            }

                            // Error check.
                            if (device.appendBuffer(buffer, bytesRead.toLong(), pts.toLong()) < 0) {
                                Log.e("AmazonIVS", "Error appending to audio device buffer")
                                stopRecording()
                                return@launchMain
                            }

                        }
                        // 1000000 is the number of microseconds per second
                        pts += samples.toLong() * 1000000 / sampleRate
                        buffer.clear()

                        // Record more. Since we are using a blocking read, we are not scheduling this.
                        handler.post {
                            record()
                        }
                    }
                }
            }

        }
    }
}

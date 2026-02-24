package com.uyghur.keyboard

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * AudioRecorder — Captures raw PCM audio from the microphone.
 *
 * Records at 16 kHz, mono, 16-bit PCM — the format expected by the ASR model.
 * Audio is accumulated into an internal buffer while recording.
 * Call [startRecording] to begin and [stopRecording] to get the captured audio
 * as a normalized Float32 array in the range [-1.0, 1.0].
 *
 * Maximum recording duration is 10 seconds to prevent excessive memory usage.
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val MAX_DURATION_SECONDS = 10
        private const val MAX_SAMPLES = SAMPLE_RATE * MAX_DURATION_SECONDS
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var isRecording = false

    // Buffer to accumulate PCM samples (Int16 → later converted to Float32)
    private val pcmBuffer = ShortArray(MAX_SAMPLES)
    private var sampleCount = 0

    /**
     * Starts capturing audio from the microphone.
     * Recording happens on a background thread to avoid blocking the UI.
     *
     * @return true if recording started successfully, false if permission denied or error.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return false
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size: $bufferSize")
            return false
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission not granted", e)
            return false
        }

        sampleCount = 0
        isRecording = true
        audioRecord?.startRecording()

        // Read audio on a background thread
        recordingThread = Thread({
            Log.d(TAG, "Recording thread started")
            val chunkSize = 1600  // 100ms chunks at 16kHz
            val tempBuffer = ShortArray(chunkSize)

            while (isRecording && sampleCount < MAX_SAMPLES) {
                val remaining = MAX_SAMPLES - sampleCount
                val readSize = minOf(chunkSize, remaining)
                val read = audioRecord?.read(tempBuffer, 0, readSize) ?: 0

                if (read > 0) {
                    System.arraycopy(tempBuffer, 0, pcmBuffer, sampleCount, read)
                    sampleCount += read
                }
            }

            Log.d(TAG, "Recording thread finished. Samples: $sampleCount")
        }, "AudioRecorder-Thread")
        recordingThread?.start()

        Log.d(TAG, "Recording started at ${SAMPLE_RATE}Hz")
        return true
    }

    /**
     * Stops recording and returns the captured audio as a Float32 array.
     *
     * @return FloatArray of PCM samples normalized to [-1.0, 1.0],
     *         or empty array if nothing was recorded.
     */
    fun stopRecording(): FloatArray {
        isRecording = false

        // Wait for recording thread to finish
        try {
            recordingThread?.join(1000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for recording thread")
        }

        // Stop and release AudioRecord
        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord stop failed", e)
        }
        audioRecord?.release()
        audioRecord = null
        recordingThread = null

        if (sampleCount == 0) {
            Log.w(TAG, "No samples recorded")
            return FloatArray(0)
        }

        Log.d(TAG, "Recorded $sampleCount samples (${sampleCount / SAMPLE_RATE.toFloat()}s)")

        // Convert Int16 PCM → Float32 normalized to [-1.0, 1.0]
        val floatAudio = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
            floatAudio[i] = pcmBuffer[i] / 32768.0f
        }

        // --- DEBUG: Save it to file for analysis ---
        try {
            val debugFile = java.io.File("/data/data/com.uyghur.keyboard/files/debug_audio.pcm")
            val outputStream = java.io.DataOutputStream(java.io.FileOutputStream(debugFile))
            for (f in floatAudio) {
                outputStream.writeFloat(f)
            }
            outputStream.close()
            Log.d(TAG, "Saved debug audio to ${debugFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save debug audio", e)
        }

        return floatAudio
    }

    /**
     * Returns true if currently recording.
     */
    fun isRecording(): Boolean = isRecording
}

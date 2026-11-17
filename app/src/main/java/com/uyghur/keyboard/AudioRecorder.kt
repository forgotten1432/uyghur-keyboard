package com.uyghur.keyboard

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.uyghur.keyboard.asr.Resampler

/**
 * AudioRecorder — Captures mono 16-bit PCM from the microphone into a buffer that can be read
 * while recording is still running (for live dictation).
 *
 * IMPORTANT: the ASR model was trained on 22050 Hz audio (gheyret/uyghur-asr-ctc, data.py),
 * and its built-in mel front-end assumes 22050 Hz. Recording at 16 kHz and feeding that
 * directly made the model hear speech at the wrong pitch/speed (≈2× more word errors).
 * We record at 22050 Hz when the device supports it, otherwise at another rate and resample.
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        const val MODEL_SAMPLE_RATE = 22050
        private val CANDIDATE_RATES = intArrayOf(22050, 44100, 48000, 16000)
        const val MAX_DURATION_SECONDS = 300
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false

    /** Sample rate actually used by the microphone (may differ from MODEL_SAMPLE_RATE). */
    @Volatile var recordRate = MODEL_SAMPLE_RATE
        private set
    @Volatile private var pcmBuffer = ShortArray(0)
    /** Number of samples recorded so far (at [recordRate]). Safe to read from any thread. */
    @Volatile var sampleCount = 0
        private set

    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return false
        }

        var record: AudioRecord? = null
        for (rate in CANDIDATE_RATES) {
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) continue
            try {
                val r = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, // tuned for ASR (no heavy AGC/effects)
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 4
                )
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    record = r
                    recordRate = rate
                    break
                }
                r.release()
            } catch (e: SecurityException) {
                Log.e(TAG, "RECORD_AUDIO permission not granted", e)
                return false
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Sample rate $rate not supported", e)
            }
        }
        if (record == null) {
            Log.e(TAG, "AudioRecord failed to initialize at any sample rate")
            return false
        }

        audioRecord = record
        pcmBuffer = ShortArray(recordRate * MAX_DURATION_SECONDS)
        sampleCount = 0
        isRecording = true
        record.startRecording()

        recordingThread = Thread({
            val chunk = ShortArray(recordRate / 20) // 50 ms
            val buf = pcmBuffer
            while (isRecording && sampleCount < buf.size) {
                val toRead = minOf(chunk.size, buf.size - sampleCount)
                val read = audioRecord?.read(chunk, 0, toRead) ?: 0
                if (read > 0) {
                    System.arraycopy(chunk, 0, buf, sampleCount, read)
                    sampleCount += read   // publish after the copy (volatile write)
                }
            }
            Log.d(TAG, "Recording thread finished. Samples: $sampleCount")
        }, "AudioRecorder-Thread")
        recordingThread?.start()

        Log.d(TAG, "Recording started at ${recordRate}Hz")
        return true
    }

    /** True while the microphone is on, or the buffer is full (max duration reached). */
    fun isRecording(): Boolean = isRecording

    fun isFull(): Boolean = isRecording && sampleCount >= pcmBuffer.size

    /** Stops the microphone. The recorded samples stay readable via [read]. */
    fun stop() {
        isRecording = false
        try {
            recordingThread?.join(1000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for recording thread")
        }
        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord stop failed", e)
        }
        audioRecord?.release()
        audioRecord = null
        recordingThread = null
        Log.d(TAG, "Recording stopped: ${sampleCount / recordRate.toFloat()} s")
    }

    /** Raw samples [from, to) at [recordRate], as floats in [-1, 1]. */
    fun readRaw(from: Int, to: Int): FloatArray {
        val buf = pcmBuffer
        val end = minOf(to, sampleCount)
        if (from >= end) return FloatArray(0)
        return FloatArray(end - from) { buf[from + it] / 32768.0f }
    }

    /** Samples [from, to) (indices at [recordRate]) resampled to [MODEL_SAMPLE_RATE] for the model. */
    fun read(from: Int, to: Int): FloatArray =
        Resampler.resample(readRaw(from, to), recordRate, MODEL_SAMPLE_RATE)
}

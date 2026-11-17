package com.uyghur.keyboard

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.uyghur.keyboard.asr.Resampler

/**
 * AudioRecorder — Captures mono 16-bit PCM from the microphone.
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
        private const val MAX_DURATION_SECONDS = 30
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false

    private var recordRate = MODEL_SAMPLE_RATE
    private var pcmBuffer = ShortArray(0)
    @Volatile private var sampleCount = 0

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
            val chunk = ShortArray(recordRate / 10) // 100 ms
            while (isRecording && sampleCount < pcmBuffer.size) {
                val toRead = minOf(chunk.size, pcmBuffer.size - sampleCount)
                val read = audioRecord?.read(chunk, 0, toRead) ?: 0
                if (read > 0) {
                    System.arraycopy(chunk, 0, pcmBuffer, sampleCount, read)
                    sampleCount += read
                }
            }
            Log.d(TAG, "Recording thread finished. Samples: $sampleCount")
        }, "AudioRecorder-Thread")
        recordingThread?.start()

        Log.d(TAG, "Recording started at ${recordRate}Hz")
        return true
    }

    /**
     * Stops recording and returns float audio in [-1, 1] at [MODEL_SAMPLE_RATE] Hz,
     * with leading/trailing silence trimmed.
     */
    fun stopRecording(): FloatArray {
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

        val n = sampleCount
        if (n == 0) return FloatArray(0)

        var audio = FloatArray(n) { pcmBuffer[it] / 32768.0f }
        audio = Resampler.resample(audio, recordRate, MODEL_SAMPLE_RATE)
        audio = trimSilence(audio, MODEL_SAMPLE_RATE)
        Log.d(TAG, "Recorded ${n / recordRate.toFloat()}s @${recordRate}Hz → ${audio.size} samples @${MODEL_SAMPLE_RATE}Hz")
        return audio
    }

    fun isRecording(): Boolean = isRecording

    /** Removes silence at both ends (keeps 0.25 s padding). Leaves audio untouched if it is all quiet. */
    private fun trimSilence(x: FloatArray, rate: Int): FloatArray {
        val frame = rate / 50 // 20 ms
        if (x.size < frame * 5) return x
        val energies = FloatArray(x.size / frame) { f ->
            var e = 0f
            for (i in f * frame until (f + 1) * frame) e += x[i] * x[i]
            e / frame
        }
        val peak = energies.maxOrNull() ?: return x
        val threshold = peak * 0.002f // ~ -27 dB below the loudest frame
        val first = energies.indexOfFirst { it > threshold }
        val last = energies.indexOfLast { it > threshold }
        if (first < 0) return x
        val pad = rate / 4
        val start = maxOf(0, first * frame - pad)
        val end = minOf(x.size, (last + 1) * frame + pad)
        return x.copyOfRange(start, end)
    }
}

package com.uyghur.keyboard

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * AsrEngine — Loads the ONNX speech recognition model and runs inference.
 *
 * The model is a CTC-based network trained on Uyghur Latin (ULY) script:
 *   Input:  raw 16 kHz float32 waveform, shape [length]
 *   Output: LogSoftmax probabilities, shape [1, 34, time_steps]
 *
 * 34 output classes = blank + 32 Latin Uyghur chars + space
 */
class AsrEngine(context: Context) {

    companion object {
        private const val TAG = "AsrEngine"
        private const val MODEL_FILE = "uyghur_asr.onnx"

        /**
         * Uyghur Latin (ULY) vocabulary — 34 classes.
         * Reverse-engineered from Wav2Vec2/Fairseq CTC token mappings:
         * 0, 1, 2: blank / special tokens
         * 3..28: English alphabet a..z
         * 29..32: special characters (word separator at 29, then ö, é, ü)
         * 33: space
         */
        private val VOCAB: Array<String> = arrayOf(
            "",    // 0: blank
            "",    // 1: pad
            "",    // 2: unk
            "a",   // 3
            "b",   // 4
            "c",   // 5
            "d",   // 6
            "e",   // 7
            "f",   // 8
            "g",   // 9
            "h",   // 10
            "i",   // 11
            "j",   // 12
            "k",   // 13
            "l",   // 14
            "m",   // 15
            "n",   // 16
            "o",   // 17
            "p",   // 18
            "q",   // 19
            "r",   // 20
            "s",   // 21
            "t",   // 22
            "u",   // 23
            "v",   // 24
            "w",   // 25
            "x",   // 26
            "y",   // 27
            "z",   // 28
            " ",   // 29: Word separator! (model outputs this between words)
            "é",   // 30
            "ö",   // 31
            "ü",   // 32
            " "    // 33: alternative space
        )

        /**
         * Mapping from Latin Uyghur (ULY) to Arabic Uyghur script.
         */
        private val LATIN_TO_ARABIC = mapOf(
            "a" to "ا", "e" to "ە", "é" to "ې", "i" to "ى",
            "o" to "و", "ö" to "ۆ", "u" to "ۇ", "ü" to "ۈ",
            "b" to "ب", "p" to "پ", "t" to "ت", "j" to "ج",
            "ch" to "چ", "x" to "خ", "d" to "د", "r" to "ر",
            "z" to "ز", "zh" to "ژ", "s" to "س", "sh" to "ش",
            "gh" to "غ", "g" to "گ", "f" to "ف", "q" to "ق",
            "k" to "ك", "ng" to "ڭ", "l" to "ل", "m" to "م",
            "n" to "ن", "h" to "ھ", "w" to "ۋ", "y" to "ي",
            " " to " ", "'" to ""
        )
    }

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        Log.d(TAG, "Loading ONNX model...")
        val modelFile = java.io.File(context.filesDir, MODEL_FILE)
        if (!modelFile.exists()) {
            Log.d(TAG, "Copying model to internal storage...")
            context.assets.open(MODEL_FILE).use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
            Log.d(TAG, "Model copied: ${modelFile.length()} bytes")
        }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
        }
        session = ortEnv.createSession(modelFile.absolutePath, opts)
        Log.d(TAG, "ONNX model loaded. Inputs: ${session.inputNames}, Outputs: ${session.outputNames}")
    }

    /**
     * Transcribes raw audio to Uyghur Arabic script text.
     */
    fun transcribe(audio: FloatArray): String {
        if (audio.isEmpty()) return ""

        Log.d(TAG, "Running inference on ${audio.size} samples (${audio.size / 16000f}s)")

        // Wav2Vec2 models typically require zero-mean, unit-variance normalization
        val mean = audio.average().toFloat()
        var variance = 0f
        for (f in audio) {
            val dev = f - mean
            variance += dev * dev
        }
        variance /= audio.size
        val stdDev = kotlin.math.sqrt(variance.toDouble() + 1e-7).toFloat()

        val normalizedAudio = FloatArray(audio.size)
        for (i in audio.indices) {
            normalizedAudio[i] = (audio[i] - mean) / stdDev
        }

        try {
            val inputTensor = OnnxTensor.createTensor(
                ortEnv,
                FloatBuffer.wrap(normalizedAudio),
                longArrayOf(normalizedAudio.size.toLong())
            )

            val latinText = inputTensor.use { tensor ->
                session.run(mapOf("input" to tensor)).use { results ->
                    val outputTensor = results[0] as OnnxTensor
                    val shape = outputTensor.info.shape
                    Log.d(TAG, "Output shape: [${shape.joinToString(", ")}]")

                    val buf = outputTensor.floatBuffer
                    val totalSize = buf.remaining()
                    val rawData = FloatArray(totalSize)
                    buf.get(rawData)

                    val numClasses = shape[1].toInt()
                    val timeSteps = shape[2].toInt()

                    val logProbs = Array(timeSteps) { t ->
                        FloatArray(numClasses) { c ->
                            rawData[c * timeSteps + t]
                        }
                    }

                    val decoded = ctcGreedyDecode(logProbs)
                    Log.d(TAG, "Transcribed Latin: \"$decoded\"")

                    // Convert to Arabic script (UEY)
                    val arabicText = latinToArabic(decoded)
                    Log.d(TAG, "Transcribed Arabic: \"$arabicText\"")
                    arabicText
                }
            }

            return latinText
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            return ""
        }
    }

    private fun ctcGreedyDecode(logProbs: Array<FloatArray>): String {
        val result = StringBuilder()
        var prevIndex = -1
        val nonBlankIndices = mutableListOf<Int>()

        for (t in logProbs.indices) {
            var bestIdx = 0
            var bestVal = logProbs[t][0]
            for (c in 1 until logProbs[t].size) {
                if (logProbs[t][c] > bestVal) {
                    bestVal = logProbs[t][c]
                    bestIdx = c
                }
            }

            if (bestIdx != 0 && bestIdx != prevIndex) {
                nonBlankIndices.add(bestIdx)
                if (bestIdx < VOCAB.size) {
                    result.append(VOCAB[bestIdx])
                }
            }
            prevIndex = bestIdx
        }

        Log.d(TAG, "All non-blank indices: $nonBlankIndices")
        return result.toString()
    }

    /**
     * Converts Latin Uyghur text to Arabic Uyghur script.
     * Handles digraphs first, then characters. Adds Hemze (ئ) before word-initial vowels.
     */
    private fun latinToArabic(latin: String): String {
        var text = latin.lowercase()
        // Replace digraphs first
        val digraphs = listOf("ch", "gh", "sh", "zh", "ng")
        for (dg in digraphs) {
            val arabic = LATIN_TO_ARABIC[dg] ?: dg
            text = text.replace(dg, arabic)
        }

        // Add Hemze (ئ) before vowels at the start of a word
        // Vowels in Latin: a, e, é, i, o, ö, u, ü
        val vowels = listOf('a', 'e', 'é', 'i', 'o', 'ö', 'u', 'ü')
        val sb = StringBuilder()
        var isWordStart = true

        for (ch in text) {
            if (ch == ' ' || ch == '\'') {
                isWordStart = true
                if (ch == ' ') sb.append(' ')
                // Ignore apostrophe as it's often generated at the end
                continue
            }

            if (isWordStart && vowels.contains(ch)) {
                sb.append("ئ")
            }
            isWordStart = false

            val mapped = LATIN_TO_ARABIC[ch.toString()]
            sb.append(mapped ?: ch)
        }
        return sb.toString()
    }

    fun close() {
        session.close()
        ortEnv.close()
        Log.d(TAG, "ONNX session closed")
    }
}

package com.uyghur.keyboard

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.uyghur.keyboard.asr.CharLanguageModel
import com.uyghur.keyboard.asr.CtcDecoder
import com.uyghur.keyboard.asr.UyghurScript
import java.io.File
import java.nio.FloatBuffer

/**
 * AsrEngine — runs the Uyghur CTC model (gheyret/uyghur-asr-ctc, trained on THUYG-20).
 *
 *   Input  "input":  raw float32 waveform at 22050 Hz, shape [length]. The model normalises
 *                    loudness and computes the mel spectrogram itself — do NOT pre-normalise.
 *   Output "output": log-softmax, shape [1, 34, time]
 *
 * Decoding: CTC prefix beam search + character n-gram LM, then ULY Latin → Arabic script.
 */
class AsrEngine(context: Context) {

    companion object {
        private const val TAG = "AsrEngine"
        private const val MODEL_FILE = "uyghur_asr_int8.onnx"
        private const val LM_FILE = "uyghur_char_lm.txt"
    }

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val lm: CharLanguageModel?

    init {
        val modelFile = copyAssetIfChanged(context, MODEL_FILE)
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = ortEnv.createSession(modelFile.absolutePath, opts)
        Log.d(TAG, "Model loaded (${modelFile.length() / 1_000_000} MB). Inputs: ${session.inputNames}")

        lm = try {
            context.assets.open(LM_FILE).use { CharLanguageModel.load(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Char LM not loaded, falling back to plain beam search", e)
            null
        }
    }

    /**
     * Copies a model asset to internal storage. Re-copies when the size changed (new APK with a
     * new model, or a previously broken/partial copy) and writes atomically via a temp file.
     */
    private fun copyAssetIfChanged(context: Context, name: String): File {
        val target = File(context.filesDir, name)
        val assetSize = try {
            context.assets.openFd(name).use { it.length }   // works because .onnx is stored uncompressed
        } catch (e: Exception) {
            -1L
        }
        if (target.exists() && (assetSize < 0 || target.length() == assetSize)) return target

        Log.d(TAG, "Copying $name to internal storage (asset size $assetSize)")
        val tmp = File(context.filesDir, "$name.tmp")
        context.assets.open(name).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
        if (tmp.length() < 1_000_000) {
            tmp.delete()
            error("$name is only ${tmp.length()} bytes — the APK contains a Git LFS pointer, not the model. Build with LFS enabled.")
        }
        if (!tmp.renameTo(target)) {
            target.delete(); tmp.renameTo(target)
        }
        // remove the old fp32 model from previous versions to free ~280 MB
        File(context.filesDir, "uyghur_asr.onnx").delete()
        return target
    }

    data class Result(val latin: String, val arabic: String)

    /** Transcribes 22050 Hz audio. Call from a background thread. */
    fun transcribe(audio: FloatArray): Result {
        if (audio.size < AudioRecorder.MODEL_SAMPLE_RATE / 5) return Result("", "")
        val t0 = System.currentTimeMillis()

        val logProbs: Array<FloatArray> =
            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(audio), longArrayOf(audio.size.toLong())).use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { results ->
                    val out = results[0] as OnnxTensor
                    val shape = out.info.shape            // [1, classes, time]
                    val classes = shape[1].toInt()
                    val time = shape[2].toInt()
                    val raw = FloatArray(classes * time)
                    out.floatBuffer.get(raw)
                    Array(time) { t -> FloatArray(classes) { c -> raw[c * time + t] } }
                }
            }
        val t1 = System.currentTimeMillis()

        val latin = CtcDecoder.beamSearch(logProbs, lm)
        val arabic = UyghurScript.latinToArabic(latin)
        Log.d(TAG, "inference ${t1 - t0} ms, decode ${System.currentTimeMillis() - t1} ms: \"$latin\" → \"$arabic\"")
        return Result(latin, arabic)
    }

    fun close() {
        session.close()  // OrtEnvironment is a process-wide singleton; don't close it
        Log.d(TAG, "ONNX session closed")
    }
}

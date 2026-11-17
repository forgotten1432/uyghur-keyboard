package com.uyghur.keyboard.asr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Windowed-sinc resampler. Used when the phone cannot record at 22050 Hz directly
 * (the model was trained on 22050 Hz audio; feeding it 16 kHz audio shifts pitch and speed
 * and roughly doubles the word error rate).
 */
object Resampler {
    fun resample(input: FloatArray, fromRate: Int, toRate: Int, halfWidth: Int = 16): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val ratio = toRate.toDouble() / fromRate
        val outLen = (input.size * ratio).toInt()
        val out = FloatArray(outLen)
        val cutoff = min(1.0, ratio) * 0.97          // low-pass when downsampling
        val step = 1.0 / ratio
        for (n in 0 until outLen) {
            val center = n * step
            val left = floor(center).toInt() - halfWidth + 1
            var acc = 0.0
            var wsum = 0.0
            for (k in left until left + 2 * halfWidth) {
                if (k < 0 || k >= input.size) continue
                val x = k - center
                val sinc = if (x == 0.0) cutoff else sin(PI * cutoff * x) / (PI * x)
                val win = 0.5 + 0.5 * cos(PI * x / halfWidth)  // Hann window
                val w = sinc * win
                acc += input[k] * w
                wsum += w
            }
            out[n] = if (wsum != 0.0) (acc / wsum).toFloat() else 0f
        }
        return out
    }
}

package com.uyghur.keyboard.asr

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {
    private fun sine(rate: Int, hz: Double, sec: Double) =
        FloatArray((rate * sec).toInt()) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun rms(x: FloatArray, from: Int, to: Int) = sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))

    @Test fun lengthFollowsRateRatio() {
        assertEquals(22050, Resampler.resample(sine(16000, 440.0, 1.0), 16000, 22050).size)
        assertEquals(22050, Resampler.resample(sine(44100, 440.0, 1.0), 44100, 22050).size)
    }

    @Test fun speechBandAmplitudeIsPreserved() {
        for (rate in listOf(16000, 44100, 48000)) {
            val out = Resampler.resample(sine(rate, 1000.0, 1.0), rate, 22050)
            assertEquals("from $rate Hz", 0.5 / sqrt(2.0), rms(out, 1000, out.size - 1000), 0.01)
        }
    }

    @Test fun sameRateIsUnchanged() {
        val x = sine(22050, 300.0, 0.1)
        assertEquals(x.toList(), Resampler.resample(x, 22050, 22050).toList())
    }
}

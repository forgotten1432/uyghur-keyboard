package com.uyghur.keyboard.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class SpeechSegmenterTest {
    private val rate = 22050
    private val rnd = Random(1)

    private fun quiet(sec: Double) = FloatArray((rate * sec).toInt()) { (rnd.nextFloat() - 0.5f) * 0.004f }
    private fun voice(sec: Double) = FloatArray((rate * sec).toInt()) {
        // syllable-like bursts (4 Hz) of a 200 Hz tone plus noise
        val env = 0.6 + 0.4 * sin(2 * PI * 4 * it / rate)
        (0.2 * env * sin(2 * PI * 200 * it / rate) + (rnd.nextFloat() - 0.5) * 0.02).toFloat()
    }

    /** Feeds the stream in 50 ms chunks like the live loop does; returns all decisions. */
    private fun run(stream: FloatArray): List<SpeechSegmenter.Decision> {
        val seg = SpeechSegmenter(rate)
        val out = mutableListOf<SpeechSegmenter.Decision>()
        var pos = 0
        val chunk = rate / 20
        while (pos < stream.size) {
            val end = minOf(stream.size, pos + chunk)
            seg.feed(stream, pos, end); pos = end
            val d = seg.decide()
            if (d != SpeechSegmenter.Decision.None) out += d
        }
        val last = seg.decide(final = true)
        if (last != SpeechSegmenter.Decision.None) out += last
        return out
    }

    @Test fun longPausesSplitPhrases() {
        val stream = quiet(1.0) + voice(2.0) + quiet(1.5) + voice(1.5) + quiet(1.5)
        val finals = run(stream).filterIsInstance<SpeechSegmenter.Decision.Final>()
        assertEquals(2, finals.size)
        // first phrase: speech is 1.0–3.0 s (+0.4 s end padding)
        assertTrue(finals[0].startSample <= rate * 1.0 && finals[0].startSample >= rate * 0.6)
        assertTrue(finals[0].endSample >= rate * 3.3 && finals[0].endSample <= rate * 3.5)
        // second phrase: speech is 4.5–6.0 s
        assertTrue(finals[1].startSample <= rate * 4.5 && finals[1].endSample >= rate * 6.0)
    }

    @Test fun shortThinkingPausesDoNotSplit() {
        val stream = quiet(1.0) + voice(1.5) + quiet(0.7) + voice(1.5) + quiet(1.5)
        assertEquals(1, run(stream).count { it is SpeechSegmenter.Decision.Final })
    }

    @Test fun stablePartialHidesWordInProgress() {
        assertEquals("u sheherdiki", SpeechSegmenter.stablePartial("u sheherdiki ewz"))
        assertEquals("", SpeechSegmenter.stablePartial("qosh"))
        assertEquals("", SpeechSegmenter.stablePartial(""))
    }

    @Test fun partialsWhileSpeaking() {
        val partials = run(quiet(0.5) + voice(3.0) + quiet(1.5)).filterIsInstance<SpeechSegmenter.Decision.Partial>()
        assertTrue("expected a few partial updates, got ${partials.size}", partials.size in 3..6)
    }

    @Test fun silenceProducesNothing() {
        assertTrue(run(quiet(5.0)).isEmpty())
    }

    @Test fun stoppingMidSentenceFlushes() {
        val decisions = run(quiet(0.5) + voice(1.5))      // stop while still speaking
        val last = decisions.last()
        assertTrue(last is SpeechSegmenter.Decision.Final)
    }

    @Test fun longSpeechIsCut() {
        val finals = run(quiet(0.5) + voice(20.0) + quiet(1.5)).filterIsInstance<SpeechSegmenter.Decision.Final>()
        assertTrue(finals.size >= 2)
        assertTrue(finals.all { it.endSample - it.startSample <= rate * 15.1 })
    }
}

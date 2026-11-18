package com.uyghur.keyboard.asr

/**
 * Splits a live microphone stream into utterance segments for "type while you speak".
 *
 * The model is bidirectional (BiGRU), so it cannot emit text frame by frame. Instead:
 *  - while you speak, the current segment is re-recognised every ~0.7 s → shown as underlined
 *    (composing) text, slightly behind your voice;
 *  - when you pause (~1 s), the segment is recognised once more and committed. Shorter pauses
 *    (thinking between words) don't split the sentence: splitting at 0.5 s cut sentences into
 *    ~3 pieces and raised the word error rate from 17% to 19.5% in tests with hesitation pauses;
 *  - segments are capped (~15 s) and cut at the quietest point, so recognition cost stays bounded.
 *
 * Pure Kotlin (no Android) so it can be unit-tested on the JVM.
 */
class SpeechSegmenter(
    private val sampleRate: Int,
    private val endSilenceMs: Int = 1000,
    private val partialEveryMs: Int = 700,
    private val maxSegmentMs: Int = 15_000,
    private val endPadMs: Int = 400,
    private val prerollMs: Int = 300,
) {
    companion object {
        /**
         * Text to show while still speaking: everything except the last word, which is usually
         * still being spoken. Halves how often shown words change afterwards (12% → 6% in tests).
         */
        fun stablePartial(text: String): String {
            val t = text.trim()
            val i = t.lastIndexOf(' ')
            return if (i < 0) "" else t.substring(0, i)
        }
    }

    sealed class Decision {
        object None : Decision()
        /** Re-recognise [startSample, endSample) and show it as provisional text. */
        data class Partial(val startSample: Int, val endSample: Int) : Decision()
        /** Recognise [startSample, endSample) and commit it; the next segment starts at endSample. */
        data class Final(val startSample: Int, val endSample: Int) : Decision()
    }

    private val frame = sampleRate / 50                     // 20 ms
    private var energies = FloatArray(4096)
    private var nFrames = 0
    private var acc = 0.0
    private var accN = 0
    private var noise = -1f
    private var segStart = 0                                 // in frames
    private var lastPartialFrame = 0

    private val endSilenceFrames = endSilenceMs / 20
    private val partialEveryFrames = partialEveryMs / 20
    private val maxSegFrames = maxSegmentMs / 20
    private val minSpeechFrames = 8                          // 160 ms of speech before showing anything
    private val prerollFrames = prerollMs / 20              // audio kept before speech onset
    private val endPadFrames = endPadMs / 20                // audio kept after the last speech frame

    /** Feed newly recorded samples (float, any scale ±1). */
    fun feed(samples: FloatArray, from: Int = 0, to: Int = samples.size) {
        for (i in from until to) {
            acc += samples[i].toDouble() * samples[i]
            if (++accN == frame) {
                pushFrame((acc / frame).toFloat())
                acc = 0.0; accN = 0
            }
        }
    }

    private fun pushFrame(e: Float) {
        if (nFrames == energies.size) energies = energies.copyOf(energies.size * 2)
        energies[nFrames++] = e
        // noise floor: follows quiet frames down quickly, rises slowly (~+10%/s)
        noise = when {
            noise < 0 -> e
            e < noise -> 0.7f * noise + 0.3f * e
            else -> noise * 1.002f
        }
    }

    private fun isSpeech(i: Int) = energies[i] > maxOf(noise * 8f, 3e-6f)

    /** Call after [feed]. [final] = recording stopped: flush whatever speech is left. */
    fun decide(final: Boolean = false): Decision {
        val segFrames = nFrames - segStart
        var speech = 0
        var lastSpeech = -1
        for (i in segStart until nFrames) if (isSpeech(i)) { speech++; lastSpeech = i }

        if (final) {
            if (speech < minSpeechFrames) return Decision.None
            return finalize(minOf(nFrames, lastSpeech + endPadFrames))
        }

        if (speech < minSpeechFrames) {
            // only silence/noise so far: slide the segment start forward, keeping a short pre-roll
            if (segFrames > 50) segStart = maxOf(segStart, nFrames - prerollFrames)
            return Decision.None
        }
        // pause after speech → commit
        if (nFrames - 1 - lastSpeech >= endSilenceFrames) return finalize(lastSpeech + endPadFrames)
        // too long without a pause → cut at the quietest frame of the last 3 s
        if (segFrames >= maxSegFrames) {
            var cut = nFrames - 1
            for (i in maxOf(segStart + 1, nFrames - 150) until nFrames) if (energies[i] < energies[cut]) cut = i
            return finalize(cut)
        }
        if (nFrames - lastPartialFrame >= partialEveryFrames) {
            lastPartialFrame = nFrames
            return Decision.Partial(segStart * frame, nFrames * frame)
        }
        return Decision.None
    }

    private fun finalize(endFrame: Int): Decision.Final {
        val start = segStart
        val end = endFrame.coerceIn(start + 1, nFrames)
        segStart = end
        lastPartialFrame = end
        return Decision.Final(start * frame, end * frame)
    }
}

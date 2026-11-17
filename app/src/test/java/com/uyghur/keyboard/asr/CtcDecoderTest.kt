package com.uyghur.keyboard.asr

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

class CtcDecoderTest {
    /** Builds confident log-probs from a frame-by-frame symbol string; '_' = blank. */
    private fun frames(spec: String): Array<FloatArray> = Array(spec.length) { t ->
        val cls = if (spec[t] == '_') CtcDecoder.BLANK else CtcDecoder.VOCAB.indexOf(spec[t])
        require(cls >= 0) { "unknown symbol ${spec[t]}" }
        FloatArray(CtcDecoder.VOCAB.size) { c -> if (c == cls) ln(0.97f) else ln(0.03f / 33) }
    }

    @Test fun greedyCollapsesRepeatsAndBlanks() {
        assertEquals("salam", CtcDecoder.greedy(frames("ss_aa_l_aaa_m__")))
    }

    @Test fun blankSeparatesDoubleLetters() {
        assertEquals("ellik", CtcDecoder.greedy(frames("e_l_l_i_k")))
        assertEquals("elik", CtcDecoder.greedy(frames("e_ll_i_k")))
    }

    @Test fun spaceAndApostropheTokens() {
        assertEquals(' ', CtcDecoder.VOCAB[29])
        assertEquals('’', CtcDecoder.VOCAB[33])
        assertEquals("mu’ellim bu", CtcDecoder.greedy(frames("mu_’_e_l_l_i_m_ _b_u")))
    }

    @Test fun beamSearchWithoutLmMatchesGreedyOnConfidentInput() {
        val lp = frames("__q_o_sh__ _n_i_l_a_r__")
        assertEquals(CtcDecoder.greedy(lp), CtcDecoder.beamSearch(lp, lm = null))
    }

    @Test fun emptyInput() {
        assertEquals("", CtcDecoder.greedy(frames("_____")))
        assertEquals("", CtcDecoder.beamSearch(frames("_____"), lm = null))
    }
}

package com.uyghur.keyboard.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.exp
import kotlin.math.ln

class CharLanguageModelTest {
    // Unit tests run with the module directory (app/) as working directory.
    private val lm by lazy { File("src/main/assets/uyghur_char_lm.txt").inputStream().use { CharLanguageModel.load(it) } }
    private val symbols = "abcdefghijklmnopqrstuvwxyz éöü’".toList() + CharLanguageModel.END

    @Test fun probabilitiesSumToOne() {
        for (h in listOf("", "sal", "uyghur til", "qqq")) {
            val sum = symbols.sumOf { exp(lm.logProb(h, it)) }
            assertEquals("history '$h'", 1.0, sum, 1e-3)
        }
    }

    @Test fun realWordsAreMoreLikelyThanNoise() {
        fun score(s: String) = s.indices.sumOf { lm.logProb(s.substring(0, it), s[it]) }
        assertTrue(score("uyghur tili") > score("uyghar tulu"))
        assertTrue(score("bilen") > score("bilan"))
    }

    @Test fun decoderUsesLmToFixVowel() {
        // acoustically "yaxshi" vs "yaxsha" are a coin flip on the last vowel; the LM should pick the real word
        val v = CtcDecoder.VOCAB
        val seq = "y_a_x_s_h_?"
        val lp = Array(seq.length) { t ->
            FloatArray(v.size) { c ->
                when {
                    seq[t] == '?' && (c == v.indexOf('i') || c == v.indexOf('a')) -> ln(0.49f)
                    seq[t] == '_' && c == CtcDecoder.BLANK -> ln(0.97f)
                    seq[t] != '_' && seq[t] != '?' && c == v.indexOf(seq[t]) -> ln(0.97f)
                    else -> ln(0.001f)
                }
            }
        }
        assertEquals("yaxshi", CtcDecoder.beamSearch(lp, lm))
    }
}

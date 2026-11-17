package com.uyghur.keyboard.asr

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * CTC decoding for the Uyghur model.
 *
 * Model vocabulary (34 classes) — taken from the training code (gheyret/uyghur-asr-ctc, uyghur.py):
 *   0 <pad> (= CTC blank), 1 <sos>, 2 <eos>, then "abcdefghijklmnopqrstuvwxyz éöü’"
 *   so 29 = space, 30 = é, 31 = ö, 32 = ü, 33 = ’ (apostrophe, e.g. mu’ellim).
 */
object CtcDecoder {
    const val BLANK = 0
    val VOCAB: CharArray = ("\u0000\u0000\u0000" + "abcdefghijklmnopqrstuvwxyz éöü’").toCharArray()
    private const val NEG_INF = Double.NEGATIVE_INFINITY

    /** logProbs is [time][class]. */
    fun greedy(logProbs: Array<FloatArray>): String {
        val sb = StringBuilder()
        var prev = -1
        for (row in logProbs) {
            var best = 0
            for (c in 1 until row.size) if (row[c] > row[best]) best = c
            if (best > 2 && best != prev) sb.append(VOCAB[best])
            prev = best
        }
        return normalizeSpaces(sb.toString())
    }

    private class Beam(var pBlank: Double = NEG_INF, var pNonBlank: Double = NEG_INF) {
        fun total() = logSumExp(pBlank, pNonBlank)
    }

    /** Known words (Uyghur Latin), sorted — for the word bonus in [beamSearch]. */
    class Lexicon(private val words: Array<String>) {
        operator fun contains(w: String) = words.binarySearch(w) >= 0

        companion object {
            fun load(input: java.io.InputStream): Lexicon = Lexicon(
                input.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotEmpty() }.sorted().toTypedArray()
            )
        }
    }

    /**
     * CTC prefix beam search with character-LM shallow fusion.
     * score(prefix) = log P_ctc(prefix) + alpha * log P_lm(prefix) + beta * len(prefix)
     *                 + lexiconBonus * (number of finished words that are real dictionary words)
     * alpha/beta/bonus were tuned on held-out THUYG-20 data (WER 12.0% → 11.6% with the bonus).
     */
    fun beamSearch(
        logProbs: Array<FloatArray>,
        lm: CharLanguageModel?,
        alpha: Double = 0.3,
        beta: Double = 1.0,
        beamWidth: Int = 16,
        prune: Float = -12f,
        lexicon: Lexicon? = null,
        lexiconBonus: Double = 1.0,
    ): String {
        fun wordBonus(prefix: String): Double {
            if (lexicon == null) return 0.0
            val w = prefix.substring(prefix.lastIndexOf(' ') + 1)
            return if (w.isNotEmpty() && w in lexicon) lexiconBonus else 0.0
        }
        var beams = HashMap<String, Beam>().apply { put("", Beam(pBlank = 0.0)) }
        val lmScore = HashMap<String, Double>().apply { put("", 0.0) }
        val candidates = IntArray(VOCAB.size)

        for (row in logProbs) {
            var nCand = 0
            for (c in 3 until row.size) if (row[c] > prune) candidates[nCand++] = c
            val blankLp = row[BLANK].toDouble()
            val next = HashMap<String, Beam>(beams.size * 4)

            for ((prefix, b) in beams) {
                val pTotal = b.total()
                next.getOrPut(prefix) { Beam() }.let { it.pBlank = logSumExp(it.pBlank, pTotal + blankLp) }
                val last = if (prefix.isEmpty()) null else prefix[prefix.length - 1]

                for (i in 0 until nCand) {
                    val c = candidates[i]
                    val ch = VOCAB[c]
                    val lp = row[c].toDouble()
                    if (ch == ' ' && (last == null || last == ' ')) {
                        // leading or doubled space: does not extend the text
                        next.getOrPut(prefix) { Beam() }.let { it.pNonBlank = logSumExp(it.pNonBlank, pTotal + lp) }
                        continue
                    }
                    val newPrefix = prefix + ch
                    if (ch == last) {
                        // repeated symbol without blank collapses; with blank in between it extends
                        next.getOrPut(prefix) { Beam() }.let { it.pNonBlank = logSumExp(it.pNonBlank, b.pNonBlank + lp) }
                        next.getOrPut(newPrefix) { Beam() }.let { it.pNonBlank = logSumExp(it.pNonBlank, b.pBlank + lp) }
                    } else {
                        next.getOrPut(newPrefix) { Beam() }.let { it.pNonBlank = logSumExp(it.pNonBlank, pTotal + lp) }
                    }
                    if (newPrefix !in lmScore) {
                        val lmPart = if (lm != null) alpha * lm.logProb(prefix, ch) else 0.0
                        val word = if (ch == ' ') wordBonus(prefix) else 0.0      // a word was just finished
                        lmScore[newPrefix] = lmScore.getValue(prefix) + lmPart + beta + word
                    }
                }
            }
            beams = next.entries
                .sortedByDescending { it.value.total() + lmScore.getValue(it.key) }
                .take(beamWidth)
                .associateTo(HashMap()) { it.key to it.value }
        }

        val best = beams.keys.maxByOrNull { k ->
            var s = beams.getValue(k).total() + lmScore.getValue(k)
            if (lm != null) s += alpha * lm.logProb(k.trimEnd(), CharLanguageModel.END)
            s + wordBonus(k.trimEnd())
        } ?: ""
        return normalizeSpaces(best)
    }

    private fun normalizeSpaces(s: String) = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    private fun logSumExp(a: Double, b: Double): Double {
        if (a == NEG_INF) return b
        if (b == NEG_INF) return a
        val m = max(a, b)
        return m + ln(exp(a - m) + exp(b - m))
    }
}

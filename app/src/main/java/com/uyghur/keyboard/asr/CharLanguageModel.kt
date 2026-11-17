package com.uyghur.keyboard.asr

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.ln
import kotlin.math.max

/**
 * Character n-gram language model (interpolated absolute discounting) over Uyghur Latin (ULY) text.
 *
 * File format (produced by tools/export_char_lm.py):
 *   #charlm order=6 d=0.75 V=33
 *   <context>\t<total>\t<types>\t<ch><count>|<ch><count>|...
 * '^' pads the start of a sentence, '$' marks its end.
 *
 * The LM nudges the CTC decoder towards letter sequences that are actually written in Uyghur,
 * which fixes many of the acoustic model's vowel confusions (e/i/u, ö/ü, é/e ...).
 */
class CharLanguageModel private constructor(
    val order: Int,
    private val discount: Double,
    private val vocabSize: Int,
    private val contexts: HashMap<String, Context>,
) {
    private class Context(val total: Int, val types: Int, val counts: HashMap<Char, Int>)

    private val cache = HashMap<String, Double>(4096)

    /** log P(ch | history). [history] is the decoded text so far (without padding). */
    fun logProb(history: String, ch: Char): Double {
        val padded = PAD.repeat(order - 1) + history
        val hist = padded.substring(padded.length - (order - 1))
        val key = hist + ch
        cache[key]?.let { return it }

        var p = 1.0 / vocabSize
        for (k in 0 until order) {
            val h = if (k == 0) "" else hist.substring(hist.length - k)
            val c = contexts[h] ?: continue
            val count = c.counts[ch] ?: 0
            p = max(count - discount, 0.0) / c.total + (discount * c.types / c.total) * p
        }
        val lp = ln(p)
        if (cache.size > 200_000) cache.clear()
        cache[key] = lp
        return lp
    }

    companion object {
        const val PAD = "^"
        const val END = '$'

        fun load(input: InputStream): CharLanguageModel {
            BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16).use { r ->
                val header = r.readLine() ?: error("Empty LM file")
                require(header.startsWith("#charlm")) { "Not a charlm file" }
                val kv = header.removePrefix("#charlm").trim().split(' ')
                    .associate { it.substringBefore('=') to it.substringAfter('=') }
                val contexts = HashMap<String, Context>(100_000)
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isEmpty()) continue
                    val f = line.split('\t')
                    if (f.size < 4) continue
                    val counts = HashMap<Char, Int>()
                    for (e in f[3].split('|')) {
                        if (e.length >= 2) counts[e[0]] = e.substring(1).toInt()
                    }
                    contexts[f[0]] = Context(f[1].toInt(), f[2].toInt(), counts)
                }
                return CharLanguageModel(
                    order = kv.getValue("order").toInt(),
                    discount = kv.getValue("d").toDouble(),
                    vocabSize = kv.getValue("V").toInt(),
                    contexts = contexts,
                )
            }
        }
    }
}

package com.uyghur.keyboard.predict

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.ln

/** Read-only word list with frequencies, sorted by word (binary search for prefixes). */
class Lexicon(private val words: Array<String>, private val freqs: IntArray) {
    val size get() = words.size

    fun freq(word: String): Int {
        val i = words.binarySearch(word)
        return if (i >= 0) freqs[i] else 0
    }

    operator fun contains(word: String) = words.binarySearch(word) >= 0

    /** Indices [from, to) of words starting with [prefix]. */
    fun prefixRange(prefix: String): IntRange {
        if (prefix.isEmpty()) return 0 until words.size
        val lo = lowerBound(prefix)
        val hi = lowerBound(prefix + '￿')
        return lo until hi
    }

    fun word(i: Int) = words[i]
    fun freqAt(i: Int) = freqs[i]

    private fun lowerBound(key: String): Int {
        var lo = 0; var hi = words.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (words[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** File format: word<TAB>freq per line, sorted by word. */
        fun load(input: InputStream): Lexicon {
            val w = ArrayList<String>(160_000)
            val f = ArrayList<Int>(160_000)
            BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    w += line.substring(0, tab)
                    f += line.substring(tab + 1).toIntOrNull() ?: 1
                }
            }
            // make sure the order matches Kotlin's String comparison
            val idx = w.indices.sortedWith { a, b -> w[a].compareTo(w[b]) }
            return Lexicon(Array(w.size) { w[idx[it]] }, IntArray(w.size) { f[idx[it]] })
        }

        fun of(vararg pairs: Pair<String, Int>): Lexicon {
            val s = pairs.sortedBy { it.first }
            return Lexicon(Array(s.size) { s[it].first }, IntArray(s.size) { s[it].second })
        }
    }
}

/** Word pairs from a corpus: word1 → (word2 → count). */
class Bigrams(private val map: Map<String, Map<String, Int>>) {
    fun count(a: String, b: String) = map[a]?.get(b) ?: 0
    fun followers(a: String): Map<String, Int> = map[a] ?: emptyMap()

    companion object {
        val EMPTY = Bigrams(emptyMap())

        /** File format: "word1 word2<TAB>count" per line. */
        fun load(input: InputStream): Bigrams {
            val m = HashMap<String, HashMap<String, Int>>()
            BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    val tab = line.indexOf('\t'); val sp = line.indexOf(' ')
                    if (tab <= 0 || sp <= 0 || sp > tab) continue
                    m.getOrPut(line.substring(0, sp)) { HashMap() }[line.substring(sp + 1, tab)] =
                        line.substring(tab + 1).toIntOrNull() ?: 1
                }
            }
            return Bigrams(m)
        }
    }
}

/** What the user has typed before. Learned words and word pairs outrank the base dictionary. */
class UserHistory {
    val words = HashMap<String, Int>()
    val pairs = HashMap<String, HashMap<String, Int>>()
    @Volatile var dirty = false
        private set

    fun learn(previous: String?, word: String) {
        if (word.isBlank()) return
        words[word] = (words[word] ?: 0) + 1
        if (!previous.isNullOrBlank()) {
            val m = pairs.getOrPut(previous) { HashMap() }
            m[word] = (m[word] ?: 0) + 1
        }
        dirty = true
    }

    fun forget(word: String) {
        words.remove(word); pairs.remove(word); pairs.values.forEach { it.remove(word) }
        dirty = true
    }

    /** Lines: "w\tword\tcount" and "p\tword1\tword2\tcount". */
    fun serialize(): String = buildString {
        for ((w, c) in words) append("w\t").append(w).append('\t').append(c).append('\n')
        for ((a, m) in pairs) for ((b, c) in m) append("p\t").append(a).append('\t').append(b).append('\t').append(c).append('\n')
    }.also { dirty = false }

    companion object {
        fun parse(text: String): UserHistory {
            val h = UserHistory()
            for (line in text.lineSequence()) {
                val f = line.split('\t')
                when {
                    f.size == 3 && f[0] == "w" -> h.words[f[1]] = f[2].toIntOrNull() ?: continue
                    f.size == 4 && f[0] == "p" -> h.pairs.getOrPut(f[1]) { HashMap() }[f[2]] = f[3].toIntOrNull() ?: continue
                }
            }
            return h
        }
    }
}

/**
 * Suggestions for the suggestion strip (Samsung/Gboard style):
 * - typing a word → completions and typo corrections, best first
 * - after a space → next-word predictions
 */
class WordPredictor(
    private val lexicon: Lexicon,
    private val bigrams: Bigrams = Bigrams.EMPTY,
    private val neighbours: Map<String, Set<String>> = emptyMap(),
    private val user: UserHistory = UserHistory(),
    /** Letter pairs that are easy to confuse in this language (e.g. Uyghur ى/ې, ۇ/ۈ). */
    private val confusable: Map<Char, Set<Char>> = emptyMap(),
    /** All letters of the alphabet (for insertions in typo corrections). */
    private val alphabet: String = "",
) {
    data class Suggestions(
        /** What is being typed (may be empty). */
        val typed: String,
        /** Ranked candidates, best first (never contains [typed] unless it's also a known word). */
        val words: List<String>,
        /** True if [typed] is itself a known word. */
        val typedIsKnown: Boolean,
    )

    private fun base(w: String) = ln(1.0 + lexicon.freq(w))
    private fun userScore(w: String) = 3.0 * ln(1.0 + (user.words[w] ?: 0))
    private fun context(prev: String?, w: String): Double {
        if (prev.isNullOrEmpty()) return 0.0
        val c = bigrams.count(prev, w) + 4 * (user.pairs[prev]?.get(w) ?: 0)
        return 2.5 * ln(1.0 + c)
    }

    private fun known(w: String) = w in lexicon || (user.words[w] ?: 0) > 0

    fun score(prev: String?, w: String) = base(w) + userScore(w) + context(prev, w)

    fun suggest(previous: String?, typed: String, n: Int = 3): Suggestions {
        val prev = previous?.lowercase()
        val t = typed.lowercase()
        if (t.isEmpty()) return Suggestions("", nextWords(prev, n), false)

        val cand = HashMap<String, Double>()
        fun add(w: String, s: Double) { if (s > (cand[w] ?: Double.NEGATIVE_INFINITY)) cand[w] = s }

        // 1. completions (the word itself counts too)
        val comp = completions(t, 40)
        comp.forEach { w -> add(w, score(prev, w) - 0.15 * (w.length - t.length)) }
        user.words.keys.filter { it.startsWith(t) }.forEach { w -> add(w, score(prev, w) - 0.15 * (w.length - t.length)) }
        if (!prev.isNullOrEmpty()) {
            (bigrams.followers(prev).keys + (user.pairs[prev]?.keys ?: emptySet()))
                .filter { it.startsWith(t) }.forEach { w -> add(w, score(prev, w)) }
        }

        // 2. typo corrections (one edit), only when they are clearly better than what was typed:
        //    if the typed text is already a real word, or the start of real words, corrections pay extra
        val typedKnown = known(t)
        val extra = if (typedKnown) 3.0 else if (comp.isNotEmpty()) 2.0 else 0.0
        if (t.length >= 2) {
            for ((v, penalty0) in edits(t)) {
                val penalty = penalty0 + extra
                if (known(v)) add(v, score(prev, v) - penalty)
                if (v.length >= 4 && !typedKnown) {
                    completions(v, 3).forEach { w -> add(w, score(prev, w) - penalty - 1.0 - 0.15 * (w.length - v.length)) }
                }
            }
        }

        val ranked = cand.entries.sortedByDescending { it.value }.map { restoreCase(typed, it.key) }.distinct().take(n)
        return Suggestions(typed, ranked, typedKnown)
    }

    private fun nextWords(prev: String?, n: Int): List<String> {
        if (prev.isNullOrEmpty()) return emptyList()
        val c = HashMap<String, Double>()
        for ((w, k) in bigrams.followers(prev)) c[w] = ln(1.0 + k) + 0.1 * base(w)
        for ((w, k) in user.pairs[prev] ?: emptyMap<String, Int>()) c[w] = (c[w] ?: 0.0) + 2.0 * ln(1.0 + 4 * k) + 1.0
        return c.entries.sortedByDescending { it.value }.take(n).map { it.key }
    }

    /** Top-[k] most frequent dictionary words starting with [prefix]. */
    private fun completions(prefix: String, k: Int): List<String> {
        val range = lexicon.prefixRange(prefix)
        if (range.isEmpty()) return emptyList()
        // small top-k selection without sorting the whole (possibly large) range
        val best = ArrayList<Int>(k + 1)
        for (i in range) {
            val f = lexicon.freqAt(i)
            if (best.size < k) {
                best += i; if (best.size == k) best.sortByDescending { lexicon.freqAt(it) }
            } else if (f > lexicon.freqAt(best[k - 1])) {
                var pos = k - 1
                while (pos > 0 && lexicon.freqAt(best[pos - 1]) < f) pos--
                best.add(pos, i); best.removeAt(k)
            }
        }
        return best.map { lexicon.word(it) }
    }

    /** One-edit variants with a penalty: neighbouring/confusable substitutions are cheap. */
    private fun edits(w: String): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>()
        for (i in w.indices) {
            val ch = w[i]
            val near = (neighbours[ch.toString()] ?: emptySet()).mapNotNull { it.singleOrNull() }.toSet()
            val conf = confusable[ch] ?: emptySet()
            for (r in near + conf) if (r != ch) out += (w.substring(0, i) + r + w.substring(i + 1)) to (if (r in conf) 1.0 else 2.0)
            out += (w.substring(0, i) + w.substring(i + 1)) to 2.5                                   // extra letter typed
            if (i + 1 < w.length) out += (w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2)) to 2.5  // swapped
        }
        for (i in 0..w.length) for (a in alphabet) out += (w.substring(0, i) + a + w.substring(i)) to 3.0       // missing letter
        return out
    }

    private fun restoreCase(typed: String, w: String): String =
        if (typed.isNotEmpty() && typed[0].isUpperCase() && w.isNotEmpty()) w.replaceFirstChar { it.uppercaseChar() } else w

    companion object {
        val UYGHUR_CONFUSABLE: Map<Char, Set<Char>> = listOf(
            "ىې", "ۇۆ", "ۇۈ", "ۆۈ", "ەا", "كگ", "قغ", "سش", "زژ", "نڭ", "ۋو",
        ).flatMap { listOf(it[0] to it[1], it[1] to it[0]) }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        const val UYGHUR_ALPHABET = "ابپتجچخدرزژسشغفقكگڭلمنھوۇۆۈۋېىيەئ"
        const val ENGLISH_ALPHABET = "abcdefghijklmnopqrstuvwxyz"
    }
}

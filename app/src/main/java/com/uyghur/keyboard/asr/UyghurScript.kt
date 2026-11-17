package com.uyghur.keyboard.asr

/**
 * Uyghur Latin (ULY) → Uyghur Arabic (UEY) conversion.
 *
 * Rules:
 *  - digraphs ch, gh, sh, zh, ng are single letters; an apostrophe between them breaks the digraph
 *    (n’g → ن + گ).
 *  - every syllable that starts with a vowel gets hemze (ئ): at the start of a word, after an
 *    apostrophe (mu’ellim → مۇئەللىم) and between two vowels (aile → ئائىلە).
 */
object UyghurScript {
    private val LETTERS = mapOf(
        "a" to "ا", "e" to "ە", "é" to "ې", "i" to "ى", "o" to "و", "ö" to "ۆ", "u" to "ۇ", "ü" to "ۈ",
        "b" to "ب", "p" to "پ", "t" to "ت", "j" to "ج", "ch" to "چ", "x" to "خ", "d" to "د", "r" to "ر",
        "z" to "ز", "zh" to "ژ", "s" to "س", "sh" to "ش", "gh" to "غ", "g" to "گ", "f" to "ف", "q" to "ق",
        "k" to "ك", "ng" to "ڭ", "l" to "ل", "m" to "م", "n" to "ن", "h" to "ھ", "w" to "ۋ", "v" to "ۋ",
        "y" to "ي", "c" to "س",
    )
    private val DIGRAPHS = listOf("ch", "gh", "sh", "zh", "ng")
    private val VOWELS = setOf("a", "e", "é", "i", "o", "ö", "u", "ü")
    private const val HEMZE = "ئ"

    fun latinToArabic(latin: String): String =
        latin.lowercase().replace('\'', '’').trim().split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ") { convertWord(it) }

    private fun convertWord(word: String): String {
        val sb = StringBuilder()
        var i = 0
        var prevWasVowel = false
        var syllableBreak = true // start of word
        while (i < word.length) {
            if (word[i] == '’') {
                syllableBreak = true; i++; continue
            }
            val two = if (i + 1 < word.length) word.substring(i, i + 2) else ""
            val token = if (two in DIGRAPHS) two else word[i].toString()
            i += token.length
            val isVowel = token in VOWELS
            if (isVowel && (syllableBreak || prevWasVowel)) sb.append(HEMZE)
            sb.append(LETTERS[token] ?: token)
            prevWasVowel = isVowel
            syllableBreak = false
        }
        return sb.toString()
    }
}

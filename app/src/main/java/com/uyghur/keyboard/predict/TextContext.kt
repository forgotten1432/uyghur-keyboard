package com.uyghur.keyboard.predict

/**
 * What is around the cursor, for prediction: the word being typed and the two words before it.
 * At the start of a sentence the previous word is [START] — so the keyboard also learns how you
 * usually begin a sentence (e.g. "ئەسسالامۇ").
 */
data class TextContext(val current: String, val previous: String?, val beforePrevious: String?) {
    companion object {
        const val START = "<s>"
        private val SENTENCE_END = setOf('.', '!', '?', '؟', '\n', '…')

        fun isWordChar(c: Char) = c.isLetter() || c == '’' || c == '\''

        fun parse(before: String): TextContext {
            var i = before.length
            while (i > 0 && isWordChar(before[i - 1])) i--
            val current = before.substring(i)
            val (p1, rest) = previousWord(before, i)
            val p2 = if (p1 == null || p1 == START) null else previousWord(before, rest).first
            return TextContext(current, p1, p2)
        }

        /** Word ending before index [end] (skipping spaces) and where it starts; START at a sentence start. */
        private fun previousWord(text: String, end: Int): Pair<String?, Int> {
            var j = end
            while (j > 0 && text[j - 1] == ' ') j--
            if (j == 0) return START to 0
            val c = text[j - 1]
            if (c in SENTENCE_END) return START to j
            if (!isWordChar(c)) return null to j                 // comma, quote, digit …: no context
            var k = j
            while (k > 0 && isWordChar(text[k - 1])) k--
            return text.substring(k, j) to k
        }
    }
}

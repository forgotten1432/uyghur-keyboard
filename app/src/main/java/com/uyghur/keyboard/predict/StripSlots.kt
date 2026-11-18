package com.uyghur.keyboard.predict

/**
 * Arranges suggestions in the 3 strip slots like Samsung/Gboard:
 * the best guess in the middle (bold), what you typed (if different) on one side, the runner-up on the other.
 * Returns [side1, middle, side2]; empty strings for unused slots.
 */
object StripSlots {
    fun arrange(s: WordPredictor.Suggestions): List<String> {
        val w = s.words
        if (w.isEmpty()) return if (s.typed.isNotEmpty()) listOf("", s.typed, "") else listOf("", "", "")
        val middle = w[0]
        val rest = w.drop(1).toMutableList()
        val side1 = when {
            s.typed.isNotEmpty() && !s.typed.equals(middle, ignoreCase = true) -> s.typed.also { rest.remove(it) }
            rest.isNotEmpty() -> rest.removeAt(0)
            else -> ""
        }
        val side2 = rest.firstOrNull() ?: ""
        return listOf(side1, middle, side2)
    }
}

package com.uyghur.keyboard

/**
 * KeyboardConstants — Defines the character layout for each keyboard row.
 *
 * ═══════════════════════════════════════════════════════════════════
 * KOTLIN CONCEPT: "object" — Singleton
 * ═══════════════════════════════════════════════════════════════════
 * In Kotlin, "object" creates a SINGLETON — a class with exactly one
 * instance that's created automatically. You access its members directly:
 *     KeyboardConstants.ROW_1
 *
 * This is similar to Dart's static-only classes, but more idiomatic.
 * Unlike "companion object" (which lives inside a class), a top-level
 * "object" stands on its own.
 *
 * ═══════════════════════════════════════════════════════════════════
 * UYGHUR KEYBOARD LAYOUT
 * ═══════════════════════════════════════════════════════════════════
 * Based on the standard Uyghur Arabic keyboard layout.
 * The keyboard is RTL (right-to-left), so Row 1's first character "چ"
 * appears on the RIGHT side of the screen.
 *
 * Each row is a CharArray. We use CharArray instead of String because
 * we need to iterate over individual characters to create key buttons.
 */
object KeyboardConstants {

    enum class KeyboardLanguage(val label: String) {
        UYGHUR("ئۇيغۇر"),
        ENGLISH("English")
    }

    // ─── UYGHUR ────────────────────────────
    val UYGHUR_ROW_1 = charArrayOf('چ', 'ۋ', 'ې', 'ر', 'ت', 'ي', 'ۇ', 'ڭ', 'و', 'پ')
    val UYGHUR_ROW_2 = charArrayOf('ھ', 'س', 'د', 'ا', 'ە', 'ى', 'ق', 'ك', 'ل')
    val UYGHUR_ROW_3 = charArrayOf('ز', 'ش', 'غ', 'ۈ', 'ب', 'ن', 'م', 'ئ')
    val UYGHUR_LONG_PRESS = mapOf('د' to 'ژ', 'ا' to 'ف', 'ە' to 'گ', 'ى' to 'خ', 'ق' to 'ج', 'ك' to 'ۆ')

    // ─── ENGLISH (QWERTY) ──────────────────
    val EN_ROW_1 = charArrayOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p')
    val EN_ROW_2 = charArrayOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l')
    val EN_ROW_3 = charArrayOf('z', 'x', 'c', 'v', 'b', 'n', 'm')
    val EN_LONG_PRESS = mapOf('e' to 'é', 'o' to 'ö', 'u' to 'ü', 'c' to 'ç', 's' to 'ş')

    // ─── Shifted Layer: Numbers & Symbols ──
    val ROW_1_SHIFTED = charArrayOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0')
    val ROW_2_SHIFTED = charArrayOf('@', '#', '٪', '&', '*', '-', '+', '(', ')', '!')
    val ROW_3_SHIFTED = charArrayOf('؟', '؛', ':', '"', '\'', '/', '\\', '،', '=')
}

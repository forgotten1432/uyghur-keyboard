package com.uyghur.keyboard.keyboard

/**
 * Keyboard layouts as plain data (no Android), so geometry and key logic are unit-testable.
 * Widths are in "key units": a full row is 10 units wide.
 */
enum class KeyType { CHAR, SHIFT, BACKSPACE, SYMBOLS, ALPHA, SYMBOLS_PAGE, LANGUAGE, SPACE, ENTER }

data class Key(
    val type: KeyType,
    val label: String,
    val output: String = label,
    val width: Float = 1f,
    /** Alternatives shown on long press (the first one is pre-selected). */
    val popup: List<String> = emptyList(),
    /** Small hint drawn in the key's corner (usually popup[0]). */
    val hint: String? = null,
    /** Large label (number pad digits). */
    val big: Boolean = false,
)

/** [height] is relative to a normal row (the number row is a bit lower, like on Samsung/Gboard). */
data class Row(val keys: List<Key>, val indent: Float = 0f, val height: Float = 1f)

data class Layout(val rows: List<Row>, val name: String)

enum class Language(val label: String, val rtl: Boolean, val code: String) {
    UYGHUR("ئۇيغۇرچە", true, "ug"),
    TURKISH("Türkçe", false, "tr"),
    ENGLISH("English", false, "en"),
}

/** Calculator-style keypads for number-only fields. */
enum class Numpad { NUMBER, DECIMAL, PHONE, DATETIME }

enum class ShiftState { OFF, ON, LOCKED }

object Layouts {
    private fun c(ch: String, popup: List<String> = emptyList(), w: Float = 1f, hint: Boolean = true) =
        Key(KeyType.CHAR, ch, ch, w, popup, if (hint) popup.firstOrNull() else null)

    private val DIGITS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

    fun numberRow() = Row(DIGITS.map { c(it) }, height = 0.8f)

    // ─── Uyghur (standard Uyghur layout, same key positions as the Windows/UKIJ keyboard) ───
    private val UG_1 = listOf("چ", "ۋ", "ې", "ر", "ت", "ي", "ۇ", "ڭ", "و", "پ")
    private val UG_2 = listOf("ھ", "س", "د", "ا", "ە", "ى", "ق", "ك", "ل")
    private val UG_3 = listOf("ز", "ش", "غ", "ۈ", "ب", "ن", "م", "ئ")
    /** Shift layer of the standard layout: D F G H J K → ژ ف گ خ ج ۆ */
    private val UG_SHIFT = mapOf("د" to "ژ", "ا" to "ف", "ە" to "گ", "ى" to "خ", "ق" to "ج", "ك" to "ۆ")
    private val UG_EXTRA = mapOf(
        "ې" to listOf("ى"), "ۇ" to listOf("ۆ", "ۈ"), "و" to listOf("ۆ"), "ۈ" to listOf("ۆ", "ۇ"),
        "ز" to listOf("ژ"), "غ" to listOf("گ"), "ن" to listOf("ڭ"), "ھ" to listOf("ە"),
    )

    fun uyghur(shift: ShiftState, numberRow: Boolean): Layout {
        val shifted = shift != ShiftState.OFF
        fun letter(ch: String): Key {
            val alt = UG_SHIFT[ch]
            val out = if (shifted && alt != null) alt else ch
            val popup = buildList {
                if (shifted && alt != null) add(ch) else if (alt != null) add(alt)
                UG_EXTRA[out]?.forEach { if (it != out && it !in this) add(it) }
            }
            return c(out, popup)
        }
        val top = UG_1.mapIndexed { i, ch ->
            val k = letter(ch)
            if (numberRow) k else k.copy(popup = listOf(DIGITS[i]) + k.popup, hint = DIGITS[i])
        }
        val rows = mutableListOf<Row>()
        if (numberRow) rows += numberRow()
        rows += Row(top)
        rows += Row(UG_2.map(::letter), indent = 0.5f)
        rows += Row(listOf(Key(KeyType.SHIFT, "⇧", width = 1.25f)) +
            UG_3.map { letter(it).copy(width = 0.9375f) } +
            Key(KeyType.BACKSPACE, "⌫", width = 1.25f))
        rows += bottomRow(Language.UYGHUR)
        return Layout(rows, "uyghur" + if (shifted) "-shift" else "")
    }

    // ─── English QWERTY ───
    private val EN_1 = "qwertyuiop".map { it.toString() }
    private val EN_2 = "asdfghjkl".map { it.toString() }
    private val EN_3 = "zxcvbnm".map { it.toString() }
    private val EN_EXTRA = mapOf(
        "e" to listOf("é", "è", "ê", "ë"), "a" to listOf("à", "á", "â", "ä"), "u" to listOf("ü", "ú", "û"),
        "o" to listOf("ö", "ó", "ô"), "i" to listOf("ı", "í", "î"), "c" to listOf("ç"), "s" to listOf("ş", "ß"),
        "g" to listOf("ğ"), "n" to listOf("ñ"),
    )

    fun english(shift: ShiftState, numberRow: Boolean): Layout {
        val up = shift != ShiftState.OFF
        fun letter(ch: String): Key {
            val popup = (EN_EXTRA[ch] ?: emptyList()).map { if (up) it.uppercase() else it }
            return c(if (up) ch.uppercase() else ch, popup, hint = false)
        }
        val top = EN_1.mapIndexed { i, ch ->
            val k = letter(ch)
            if (numberRow) k else k.copy(popup = listOf(DIGITS[i]) + k.popup, hint = DIGITS[i])
        }
        val rows = mutableListOf<Row>()
        if (numberRow) rows += numberRow()
        rows += Row(top)
        rows += Row(EN_2.map(::letter), indent = 0.5f)
        rows += Row(listOf(Key(KeyType.SHIFT, "⇧", width = 1.5f)) + EN_3.map(::letter) +
            Key(KeyType.BACKSPACE, "⌫", width = 1.5f))
        rows += bottomRow(Language.ENGLISH)
        return Layout(rows, "english" + if (up) "-shift" else "")
    }

    // ─── Turkish Q (12 keys per row, like Samsung/Gboard Türkçe Q) ───
    private val TR = java.util.Locale("tr", "TR")
    private val TR_1 = listOf("q", "w", "e", "r", "t", "y", "u", "ı", "o", "p", "ğ", "ü")
    private val TR_2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ş", "i")
    private val TR_3 = listOf("z", "x", "c", "v", "b", "n", "m", "ö", "ç")
    private val TR_EXTRA = mapOf(
        "a" to listOf("â"), "i" to listOf("î"), "u" to listOf("û"), "e" to listOf("é"), "o" to listOf("ô"),
    )

    fun turkish(shift: ShiftState, numberRow: Boolean): Layout {
        val up = shift != ShiftState.OFF
        fun letter(ch: String): Key {
            val popup = (TR_EXTRA[ch] ?: emptyList()).map { if (up) it.uppercase(TR) else it }
            return c(if (up) ch.uppercase(TR) else ch, popup, hint = false)
        }
        val top = TR_1.mapIndexed { i, ch ->
            val k = letter(ch)
            if (numberRow || i >= 10) k else k.copy(popup = listOf(DIGITS[i]) + k.popup, hint = DIGITS[i])
        }
        val rows = mutableListOf<Row>()
        if (numberRow) rows += numberRow()
        rows += Row(top)
        rows += Row(TR_2.map(::letter), indent = 0.5f)
        rows += Row(listOf(Key(KeyType.SHIFT, "⇧", width = 1.5f)) + TR_3.map(::letter) +
            Key(KeyType.BACKSPACE, "⌫", width = 1.5f))
        rows += bottomRow(Language.TURKISH)
        return Layout(rows, "turkish" + if (up) "-shift" else "")
    }

    fun letters(lang: Language, shift: ShiftState, numberRow: Boolean) = when (lang) {
        Language.UYGHUR -> uyghur(shift, numberRow)
        Language.TURKISH -> turkish(shift, numberRow)
        Language.ENGLISH -> english(shift, numberRow)
    }

    // ─── Number pads (4 × 4, big keys) ───
    fun numpad(kind: Numpad): Layout {
        fun d(s: String, popup: List<String> = emptyList()) = Key(KeyType.CHAR, s, s, 2.5f, popup, null, big = true)
        fun f(s: String, popup: List<String> = emptyList()) = Key(KeyType.CHAR, s, s, 2.5f, popup, null)
        val side = when (kind) {
            Numpad.PHONE -> listOf(f("-", listOf("(", ")", "/", ".")), Key(KeyType.SPACE, "␣", " ", 2.5f))
            Numpad.DATETIME -> listOf(f("-"), f(":"))
            else -> listOf(f("-"), Key(KeyType.SPACE, "␣", " ", 2.5f))
        }
        val bottom = when (kind) {
            Numpad.PHONE -> listOf(f("*", listOf("#", ",", ";")), d("0", listOf("+")), f("#"))
            Numpad.DATETIME -> listOf(f("/"), d("0"), f("."))
            Numpad.DECIMAL -> listOf(f(","), d("0"), f("."))
            Numpad.NUMBER -> listOf(f(","), d("0"), f("."))
        }
        val rows = listOf(
            Row(listOf(d("1"), d("2"), d("3"), side[0])),
            Row(listOf(d("4"), d("5"), d("6"), side[1])),
            Row(listOf(d("7"), d("8"), d("9"), Key(KeyType.BACKSPACE, "⌫", width = 2.5f))),
            Row(bottom + Key(KeyType.ENTER, "↵", "\n", width = 2.5f)),
        )
        return Layout(rows, "numpad-" + kind.name.lowercase())
    }

    private fun comma(lang: Language) = if (lang == Language.UYGHUR)
        c("،", listOf(",", "؛", ";")) else c(",", listOf(";", ":"), hint = false)

    private fun period(lang: Language) = if (lang == Language.UYGHUR)
        c(".", listOf("؟", "!", ":", "«", "»", "-", "\"", "'", "…"), hint = false)
    else c(".", listOf("?", "!", ":", "-", "\"", "'", "…"), hint = false)

    private fun bottomRow(lang: Language) = Row(listOf(
        Key(KeyType.SYMBOLS, "!#1", width = 1.5f),
        Key(KeyType.LANGUAGE, "🌐"),
        comma(lang),
        Key(KeyType.SPACE, lang.label, " ", width = 4f),
        period(lang),
        Key(KeyType.ENTER, "↵", "\n", width = 1.5f),
    ))

    // ─── Symbols (2 pages) ───
    fun symbols(page: Int, lang: Language): Layout {
        val ug = lang == Language.UYGHUR
        val q = if (ug) "؟" else "?"
        val semi = if (ug) "؛" else ";"
        val rows = if (page == 1) listOf(
            Row(DIGITS.map { c(it) }),
            Row(listOf("@", "#", "₺", "_", "&", "-", "+", "(", ")", "/").map { c(it) }),
            Row(listOf(Key(KeyType.SYMBOLS_PAGE, "1/2", width = 1.5f)) +
                listOf("*", "\"", "'", ":", semi, "!", q).map { c(it) } +
                Key(KeyType.BACKSPACE, "⌫", width = 1.5f)),
        ) else listOf(
            Row(listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map { c(it) }),
            Row(listOf("£", "€", "$", "¥", "°", "^", "=", "{", "}", "\\").map { c(it) }),
            Row(listOf(Key(KeyType.SYMBOLS_PAGE, "2/2", width = 1.5f)) +
                listOf("%", "«", "»", "[", "]", "<", ">").map { c(it) } +
                Key(KeyType.BACKSPACE, "⌫", width = 1.5f)),
        )
        val bottom = Row(listOf(
            Key(KeyType.ALPHA, if (ug) "ئا" else "ABC", width = 1.5f),
            comma(lang),
            Key(KeyType.SPACE, "", " ", width = 5f),
            period(lang),
            Key(KeyType.ENTER, "↵", "\n", width = 1.5f),
        ))
        return Layout(rows + bottom, "symbols$page")
    }

    /**
     * Keys whose centres are close on the keyboard — used to rank typo corrections
     * (a mistyped neighbouring key is a much more likely error than a far one).
     */
    fun neighbours(layout: Layout, radius: Float = 1.3f): Map<String, Set<String>> {
        val pos = mutableMapOf<String, Pair<Float, Float>>()
        layout.rows.forEachIndexed { r, row ->
            var x = row.indent
            for (k in row.keys) {
                if (k.type == KeyType.CHAR && k.output.length == 1) pos.putIfAbsent(k.output, (x + k.width / 2) to r.toFloat())
                x += k.width
            }
        }
        return pos.mapValues { (ch, p) ->
            pos.filter { (o, q) ->
                o != ch && Math.hypot((p.first - q.first).toDouble(), (p.second - q.second).toDouble()) <= radius
            }.keys
        }
    }
}

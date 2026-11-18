package com.uyghur.keyboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutTest {
    private val all = listOf(
        Layouts.uyghur(ShiftState.OFF, false), Layouts.uyghur(ShiftState.ON, false), Layouts.uyghur(ShiftState.OFF, true),
        Layouts.english(ShiftState.OFF, false), Layouts.english(ShiftState.ON, true),
        Layouts.symbols(1, Language.UYGHUR), Layouts.symbols(2, Language.ENGLISH),
    ) + Numpad.values().map { Layouts.numpad(it) }

    @Test fun turkishRowsAreTwelveUnits() {
        val l = Layouts.turkish(ShiftState.OFF, false)
        for (r in l.rows.dropLast(1)) assertEquals(12.0, r.keys.sumOf { it.width.toDouble() } + 2 * r.indent, 0.001)
        val keys = l.rows.flatMap { it.keys }.map { it.output }
        for (ch in listOf("ğ", "ü", "ş", "ı", "i", "ö", "ç")) assertTrue(ch in keys)
        val up = Layouts.turkish(ShiftState.ON, false).rows.flatMap { it.keys }.map { it.output }
        assertTrue("İ" in up && "I" in up)
    }

    @Test fun numpadsHaveAllDigits() {
        for (kind in Numpad.values()) {
            val out = Layouts.numpad(kind).rows.flatMap { it.keys }.map { it.output }
            for (d in 0..9) assertTrue("$kind missing $d", d.toString() in out)
            assertEquals(4, Layouts.numpad(kind).rows.size)
        }
        assertTrue("+" in Layouts.numpad(Numpad.PHONE).rows.flatMap { it.keys }.flatMap { it.popup })
    }

    @Test fun everyRowIsTenUnitsOrLess() {
        for (l in all.filter { !it.name.startsWith("turkish") }) for (r in l.rows) {
            val units = r.keys.sumOf { it.width.toDouble() } + 2 * r.indent
            assertTrue("${l.name}: row is $units units", units <= 10.0001)
        }
    }

    @Test fun allUyghurLettersAreReachable() {
        val base = Layouts.uyghur(ShiftState.OFF, false)
        val shift = Layouts.uyghur(ShiftState.ON, false)
        val typed = (base.rows + shift.rows).flatMap { it.keys }.filter { it.type == KeyType.CHAR }
            .flatMap { listOf(it.output) + it.popup }.toSet()
        for (ch in "ابپتجچخدرزژسشغفقكگڭلمنھوۇۆۈۋېىيەئ") assertTrue("missing $ch", ch.toString() in typed)
    }

    @Test fun shiftLayerHasStandardLetters() {
        val keys = Layouts.uyghur(ShiftState.ON, false).rows.flatMap { it.keys }.map { it.output }
        for (ch in listOf("ژ", "ف", "گ", "خ", "ج", "ۆ")) assertTrue(ch in keys)
    }

    @Test fun topRowHasNumberHintsWithoutNumberRow() {
        val top = Layouts.uyghur(ShiftState.OFF, false).rows[0].keys
        assertEquals("1", top[0].hint); assertEquals("0", top[9].hint)
        assertEquals(5, Layouts.uyghur(ShiftState.OFF, true).rows.size)
    }

    @Test fun neighboursAreAdjacentKeys() {
        val n = Layouts.neighbours(Layouts.english(ShiftState.OFF, false))
        assertTrue("w" in n.getValue("q") && "a" in n.getValue("q"))
        assertTrue("p" !in n.getValue("q"))
    }
}

package com.uyghur.keyboard.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class UyghurScriptTest {
    private fun conv(s: String) = UyghurScript.latinToArabic(s)

    @Test fun simpleWords() {
        assertEquals("ئۇيغۇر تىلى", conv("uyghur tili"))
        assertEquals("شىنجاڭ", conv("shinjang"))
        assertEquals("قوشنىلارنىڭ", conv("qoshnilarning"))
    }

    @Test fun hemzeAtWordStartAndBetweenVowels() {
        assertEquals("ئائىلە", conv("aile"))
        assertEquals("ئۆي", conv("öy"))
    }

    @Test fun apostropheMarksSyllableBreak() {
        assertEquals("مۇئەللىم", conv("mu’ellim"))
        assertEquals("مۇئەللىم", conv("mu'ellim"))      // ASCII apostrophe too
        assertEquals("سائەت", conv("sa’et"))
    }

    @Test fun apostropheBreaksDigraph() {
        assertEquals("ئەنگلىيە", conv("en’gliye"))      // n + g, not ng
        assertEquals("ئەڭ", conv("eng"))
    }

    @Test fun extraSpacesAreCollapsed() {
        assertEquals("بۇ ئۆي", conv("  bu   öy "))
    }
}

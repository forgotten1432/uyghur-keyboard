package com.uyghur.keyboard.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class PhraseLearningTest {

    @Test fun contextAtSentenceStart() {
        val c = TextContext.parse("")
        assertEquals("", c.current); assertEquals(TextContext.START, c.previous); assertNull(c.beforePrevious)
        assertEquals(TextContext.START, TextContext.parse("Yaxshi. Bu").previous)
        assertEquals(TextContext.START, TextContext.parse("سالام؟ ").previous)
    }

    @Test fun contextTwoWordsBack() {
        val c = TextContext.parse("ئەسسالامۇ ئەلەيكۇم قان")
        assertEquals("قان", c.current); assertEquals("ئەلەيكۇم", c.previous); assertEquals("ئەسسالامۇ", c.beforePrevious)
        val d = TextContext.parse("ئەسسالامۇ ")
        assertEquals("", d.current); assertEquals("ئەسسالامۇ", d.previous); assertEquals(TextContext.START, d.beforePrevious)
    }

    @Test fun commaBreaksContext() {
        assertNull(TextContext.parse("salam, ").previous)
    }

    /** Simulates typing like the keyboard does: every finished word is learned with its context. */
    private fun type(h: UserHistory, sentence: String) {
        val words = sentence.split(' ')
        var p1: String? = TextContext.START; var p2: String? = null
        for (w in words) { h.learn(p1, w, p2); p2 = p1; p1 = w }
    }

    @Test fun learnsGreetingAfterAFewTimes() {
        val lexicon = File("src/main/assets/uyghur_dict.txt").inputStream().use { Lexicon.load(it) }
        val bigrams = File("src/main/assets/uyghur_bigrams.txt").inputStream().use { Bigrams.load(it) }
        val h = UserHistory()
        val p = WordPredictor(lexicon, bigrams, user = h, alphabet = WordPredictor.UYGHUR_ALPHABET)
        repeat(3) { type(h, "ئەسسالامۇ ئەلەيكۇم قانداق ئەھۋالىڭىز") }
        // empty message: the usual first word
        assertEquals("ئەسسالامۇ", p.suggest(TextContext.parse("")).words.first())
        // after the first word, the second one
        assertEquals("ئەلەيكۇم", p.suggest(TextContext.parse("ئەسسالامۇ ")).words.first())
        // and the phrase continues
        assertEquals("قانداق", p.suggest(TextContext.parse("ئەسسالامۇ ئەلەيكۇم ")).words.first())
        assertEquals("ئەھۋالىڭىز", p.suggest(TextContext.parse("ئەسسالامۇ ئەلەيكۇم قانداق ")).words.first())
    }

    @Test fun phraseBeatsSingleWordHabit() {
        // "iyi" usually follows "çok", but after "bugün çok" the user always writes "yoruldum"
        val tr = Locale("tr", "TR")
        val lex = Lexicon.of("çok" to 1000, "iyi" to 900, "yoruldum" to 50, "bugün" to 600)
        val h = UserHistory()
        val p = WordPredictor(lex, user = h, locale = tr)
        repeat(4) { type(h, "çok iyi") }
        repeat(2) { type(h, "bugün çok yoruldum") }
        assertEquals("iyi", p.suggest(TextContext.parse("Ama çok ")).words.first())
        assertEquals("yoruldum", p.suggest(TextContext.parse("bugün çok ")).words.first())
    }

    @Test fun turkishCasing() {
        val tr = Locale("tr", "TR")
        val lex = Lexicon.of("ılık" to 50, "ıslak" to 40, "istanbul" to 900, "iyi" to 800)
        val p = WordPredictor(lex, locale = tr, alphabet = WordPredictor.TURKISH_ALPHABET)
        assertEquals("Ilık", p.suggest(null, "Il").words.first())        // I → ı, not i
        assertEquals("İstanbul", p.suggest(null, "İst").words.first())   // İ → i
    }

    @Test fun turkishDictionaryLoads() {
        val lex = File("src/main/assets/turkish_dict.txt").inputStream().use { Lexicon.load(it) }
        assertTrue(lex.size > 40_000)
        val p = WordPredictor(lex, locale = Locale("tr", "TR"), alphabet = WordPredictor.TURKISH_ALPHABET)
        assertEquals("nasıl", p.suggest(null, "nası").words.first())
        assertEquals("teşekkürler", p.suggest(null, "teşekkürlr").words.first())
    }

    @Test fun historyKeepsPhrases() {
        val h = UserHistory(); type(h, "a b c")
        val back = UserHistory.parse(h.serialize())
        assertEquals(h.triples, back.triples)
    }
}

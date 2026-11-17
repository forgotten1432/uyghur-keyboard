package com.uyghur.keyboard.predict

import com.uyghur.keyboard.keyboard.Layouts
import com.uyghur.keyboard.keyboard.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WordPredictorTest {
    private val lex = Lexicon.of("salam" to 50, "salamet" to 20, "salamu" to 5, "bilen" to 900, "bilim" to 300,
        "bir" to 1000, "yil" to 400, "bu" to 800, "yaxshi" to 600, "yaxshimu" to 100, "kitab" to 200)
    private val bigrams = Bigrams(mapOf("bu" to mapOf("yil" to 30, "kitab" to 5)))
    private val p = WordPredictor(lex, bigrams, Layouts.neighbours(Layouts.english(ShiftState.OFF, false)),
        alphabet = WordPredictor.ENGLISH_ALPHABET)

    @Test fun completesByFrequency() {
        assertEquals(listOf("salam", "salamet", "salamu"), p.suggest(null, "sal").words)
    }

    @Test fun correctsNeighbourKeyTypo() {
        assertEquals("bilen", p.suggest(null, "bilrn").words.first())   // r is next to e
    }

    @Test fun predictsNextWord() {
        assertEquals(listOf("yil", "kitab"), p.suggest("bu", "").words)
    }

    @Test fun contextRanksCompletions() {
        assertEquals("yil", p.suggest("bu", "y").words.first())
    }

    @Test fun learnsFromUser() {
        val user = UserHistory()
        val q = WordPredictor(lex, bigrams, user = user)
        repeat(3) { user.learn("salam", "dostlar") }
        assertEquals("dostlar", q.suggest(null, "dos").words.first())
        assertEquals("dostlar", q.suggest("salam", "").words.first())
    }

    @Test fun keepsCapitalisation() {
        assertEquals("Salam", p.suggest(null, "Sal").words.first())
    }

    @Test fun userHistoryRoundTrip() {
        val h = UserHistory(); h.learn("a", "b"); h.learn(null, "c")
        val back = UserHistory.parse(h.serialize())
        assertEquals(h.words, back.words); assertEquals(h.pairs, back.pairs)
    }

    @Test fun stripPutsBestInMiddleAndTypedOnSide() {
        val s = p.suggest(null, "bilrn")
        val slots = StripSlots.arrange(s)
        assertEquals("bilrn", slots[0]); assertEquals("bilen", slots[1])
    }

    @Test fun realUyghurDictionary() {
        // unit tests run with app/ as working directory
        val lexicon = File("src/main/assets/uyghur_dict.txt").inputStream().use { Lexicon.load(it) }
        assertTrue(lexicon.size > 100_000)
        val ug = WordPredictor(lexicon, Bigrams.EMPTY, Layouts.neighbours(Layouts.uyghur(ShiftState.OFF, false)),
            confusable = WordPredictor.UYGHUR_CONFUSABLE, alphabet = WordPredictor.UYGHUR_ALPHABET)
        assertEquals("ياخشى", ug.suggest(null, "ياخ").words.first())
        assertEquals("رەھمەت", ug.suggest(null, "رەھ").words.first())
        assertEquals("ياخشى", ug.suggest(null, "ياخشم").words.first())   // typo: neighbouring key
    }
}

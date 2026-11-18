package com.uyghur.keyboard.predict

import com.uyghur.keyboard.keyboard.Language
import com.uyghur.keyboard.keyboard.Layouts
import com.uyghur.keyboard.keyboard.ShiftState
import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

/** Turkish and English prediction with the dictionaries and word pairs the app ships. */
class LatinPredictionTest {
    private fun predictor(name: String, lang: Language, alphabet: String, loc: Locale): WordPredictor {
        val lexicon = File("src/main/assets/${name}_dict.txt").inputStream().use { Lexicon.load(it) }
        val bigrams = File("src/main/assets/${name}_bigrams.txt").inputStream().use { Bigrams.load(it) }
        val neighbours = Layouts.neighbours(Layouts.letters(lang, ShiftState.OFF, false))
        return WordPredictor(lexicon, bigrams, neighbours, UserHistory(), emptyMap(), alphabet, loc)
    }

    private val turkish by lazy { predictor("turkish", Language.TURKISH, WordPredictor.TURKISH_ALPHABET, Locale("tr", "TR")) }
    private val english by lazy { predictor("english", Language.ENGLISH, WordPredictor.ENGLISH_ALPHABET, Locale.ROOT) }

    private fun WordPredictor.words(before: String) = suggest(TextContext.parse(before)).words

    @Test fun `turkish completes a word`() {
        assertTrue(turkish.words("merh").toString(), "merhaba" in turkish.words("merh"))
        assertTrue("nasıl" in turkish.words("Merhaba nas"))
    }

    @Test fun `turkish predicts the next word after a space`() {
        val next = turkish.words("Bugün hava ")
        assertTrue(next.toString(), next.isNotEmpty())
        assertTrue(next.toString(), "güzel" in next || "çok" in next)
    }

    @Test fun `turkish next word uses Turkish lower case for dotted capital I`() {
        assertTrue(turkish.words("İyi ").toString(), turkish.words("İyi ").isNotEmpty())
    }

    @Test fun `english completes a word`() {
        assertTrue("help" in english.words("hel"))
        assertTrue("you" in english.words("How are y"))
    }

    @Test fun `english predicts the next word after a space`() {
        assertTrue(english.words("thank ").toString(), "you" in english.words("thank "))
        assertTrue(english.words("I don't ").toString(), "know" in english.words("I don't "))
    }

    @Test fun `no prediction after a comma`() {
        assertTrue(english.words("thank, ").isEmpty())
    }
}

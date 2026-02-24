package com.uyghur.keyboard

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object DictionaryHelper {
    private const val TAG = "DictionaryHelper"

    // Mutable list so we can update it from the Internet
    var UYGHUR_WORDS: List<String> = listOf(
        "ئەسسالامۇ", "ئەلەيكۇم", "ياخشىمۇ", "سىز", "قانداق", "رەھمەت",
        "مەن", "سەن", "ئۇ", "بىز", "سىلەر", "ئۇلار",
        "ئۇيغۇر", "تىلى", "كىتاب", "مەكتەپ", "ئىش", "ئۆي", "كۈن", "يىل",
        "قەيەردە", "نىمە", "نېمە", "قاچان", "كېلىڭ", "مېھمان", "خەيرلىك", "ئەتىگەن",
        "ۋاقىت", "پۇل", "دەرس", "بالا", "ئاتا", "ئانا", "مۇئەللىم", "ۋەتەن"
    )

    var ENGLISH_WORDS: List<String> = listOf(
        "hello", "world", "how", "are", "you", "good", "morning", "afternoon", "evening",
        "thanks", "thank", "please", "sorry", "yes", "no", "what", "where", "when", "why", "who",
        "this", "that", "there", "here", "they", "them", "their", "we", "us", "our",
        "android", "keyboard", "app", "phone", "time", "day", "night"
    )

    /**
     * Downloads a list of words from the given URL (e.g. a raw text file on GitHub) 
     * and updates the Uyghur dictionary.
     */
    suspend fun fetchUyghurWordsFromUrl(urlString: String) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching remote dictionary from $urlString")
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val newWords = mutableListOf<String>()
                    var line: String?
                    
                    while (reader.readLine().also { line = it } != null) {
                        val word = line?.trim()
                        if (!word.isNullOrEmpty()) {
                            newWords.add(word)
                        }
                    }
                    reader.close()

                    if (newWords.isNotEmpty()) {
                        // Merge or replace as you prefer. Here we replace and keep the baseline basics
                        UYGHUR_WORDS = UYGHUR_WORDS + newWords
                        Log.d(TAG, "Successfully added ${newWords.size} words from the internet.")
                    }
                } else {
                    Log.e(TAG, "Failed to fetch dictionary. HTTP Code: ${connection.responseCode}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching internet dictionary", e)
            }
            return@withContext
        }
    }

    /**
     * Finds the top completions for a given prefix in the selected language list.
     * Returns up to 3 results.
     */
    fun getSuggestions(prefix: String, language: KeyboardConstants.KeyboardLanguage): List<String> {
        if (prefix.isBlank()) return emptyList()
        val query = prefix.lowercase()

        val listToSearch = when (language) {
            KeyboardConstants.KeyboardLanguage.UYGHUR -> UYGHUR_WORDS
            KeyboardConstants.KeyboardLanguage.ENGLISH -> ENGLISH_WORDS
        }

        return listToSearch
            .filter { it.startsWith(query) && it != query } // Don't suggest the exact same word
            .take(3)
    }
}

package com.uyghur.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object DictionaryHelper {
    private const val TAG = "DictionaryHelper"
    private const val PREFS_NAME = "KeyboardDictPrefs"

    // Base dictionaries
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

    // User learned dictionaries
    // word -> frequency
    private val userWords = mutableMapOf<String, Int>()
    // previousWord -> { nextWord -> frequency }
    private val bigrams = mutableMapOf<String, MutableMap<String, Int>>()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val wordsJsonStr = prefs.getString("userWords", "{}")
            if (wordsJsonStr != null) {
                val wordsObj = JSONObject(wordsJsonStr)
                for (key in wordsObj.keys()) {
                    userWords[key] = wordsObj.getInt(key)
                }
            }

            val bigramsJsonStr = prefs.getString("bigrams", "{}")
            if (bigramsJsonStr != null) {
                val bigramsObj = JSONObject(bigramsJsonStr)
                for (key1 in bigramsObj.keys()) {
                    val innerObj = bigramsObj.getJSONObject(key1)
                    val map = mutableMapOf<String, Int>()
                    for (key2 in innerObj.keys()) {
                        map[key2] = innerObj.getInt(key2)
                    }
                    bigrams[key1] = map
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load user dictionaries", e)
        }
    }

    private fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val wordsObj = JSONObject()
            for ((k, v) in userWords) {
                wordsObj.put(k, v)
            }

            val bigramsObj = JSONObject()
            for ((k1, map) in bigrams) {
                val innerObj = JSONObject()
                for ((k2, v2) in map) {
                    innerObj.put(k2, v2)
                }
                bigramsObj.put(k1, innerObj)
            }

            prefs.edit()
                .putString("userWords", wordsObj.toString())
                .putString("bigrams", bigramsObj.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save user dictionaries", e)
        }
    }

    /**
     * Learns a single word to be suggested in the future.
     */
    fun learnWord(context: Context, word: String) {
        if (word.isBlank() || word.length < 2) return
        val w = word.lowercase().trim()
        userWords[w] = (userWords[w] ?: 0) + 1
        save(context)
    }

    /**
     * Learns a sequence of two words (for next-word prediction).
     */
    fun learnSequence(context: Context, word1: String, word2: String) {
        if (word1.isBlank() || word2.isBlank()) return
        val w1 = word1.lowercase().trim()
        val w2 = word2.lowercase().trim()
        
        val map = bigrams[w1] ?: mutableMapOf()
        map[w2] = (map[w2] ?: 0) + 1
        bigrams[w1] = map
        
        // Also learn them individually
        userWords[w1] = (userWords[w1] ?: 0) + 1
        userWords[w2] = (userWords[w2] ?: 0) + 1
        
        save(context)
    }

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
                    val newWords = mutableSetOf<String>()
                    var line: String?
                    
                    while (reader.readLine().also { line = it } != null) {
                        val word = line?.trim()
                        if (!word.isNullOrEmpty()) {
                            newWords.add(word)
                        }
                    }
                    reader.close()

                    if (newWords.isNotEmpty()) {
                        // Merge or replace as you prefer.
                        UYGHUR_WORDS = (UYGHUR_WORDS + newWords).distinct()
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
     * Get suggestions based on previous word (if current is blank) OR current word prefix.
     */
    fun getSuggestions(
        previousWord: String, 
        currentPrefix: String, 
        language: KeyboardConstants.KeyboardLanguage
    ): List<String> {
        
        val p = previousWord.lowercase().trim()
        val q = currentPrefix.lowercase().trim()

        // 1. If currently not typing inside a word, but we have a previous word -> Next Word Prediction!
        if (q.isBlank() && p.isNotBlank()) {
            val suggestions = bigrams[p]
            if (suggestions != null && suggestions.isNotEmpty()) {
                // Return top 3 next-words sorted by frequency
                return suggestions.entries
                    .sortedByDescending { it.value }
                    .take(3)
                    .map { it.key }
            }
            return emptyList()
        }

        // 2. If we are currently typing a word -> Autocomplete
        if (q.isNotBlank()) {
            val baseList = when (language) {
                KeyboardConstants.KeyboardLanguage.UYGHUR -> UYGHUR_WORDS
                KeyboardConstants.KeyboardLanguage.ENGLISH -> ENGLISH_WORDS
            }

            // A) First look in user learned words containing this prefix (sorted by desc freq)
            val learnedMatches = userWords.entries
                .filter { it.key.startsWith(q) && it.key != q }
                .sortedByDescending { it.value }
                .map { it.key }

            // B) Look in the base dictionary
            val basicMatches = baseList.filter { it.startsWith(q) && it != q }

            // Combine and return top 3 unique words
            return (learnedMatches + basicMatches).distinct().take(3)
        }

        return emptyList()
    }
}

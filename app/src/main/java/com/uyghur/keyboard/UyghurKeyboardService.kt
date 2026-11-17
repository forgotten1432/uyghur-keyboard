package com.uyghur.keyboard

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.uyghur.keyboard.asr.SpeechSegmenter
import com.uyghur.keyboard.keyboard.Key
import com.uyghur.keyboard.keyboard.KeyType
import com.uyghur.keyboard.keyboard.KeyboardTheme
import com.uyghur.keyboard.keyboard.KeyboardView
import com.uyghur.keyboard.keyboard.Language
import com.uyghur.keyboard.keyboard.Layouts
import com.uyghur.keyboard.keyboard.ShiftState
import com.uyghur.keyboard.predict.Bigrams
import com.uyghur.keyboard.predict.Lexicon
import com.uyghur.keyboard.predict.StripSlots
import com.uyghur.keyboard.predict.UserHistory
import com.uyghur.keyboard.predict.WordPredictor
import kotlinx.coroutines.*
import java.io.File

/**
 * The keyboard service: text editing logic + word prediction + voice dictation.
 * Drawing and touch handling live in [KeyboardView].
 */
class UyghurKeyboardService : InputMethodService(), KeyboardView.Listener {

    companion object {
        private const val TAG = "UyghurIME"
        private const val DOUBLE_TAP_SHIFT_MS = 350L
        private const val DOUBLE_SPACE_MS = 1200L
    }

    private lateinit var settings: KeyboardSettings
    private var view: KeyboardView? = null
    private val main = Handler(Looper.getMainLooper())

    // ─── keyboard state ───
    private var language = Language.UYGHUR
    private var shift = ShiftState.OFF
    private var lastShiftTap = 0L
    private var symbolsPage = 0                 // 0 = letters, 1/2 = symbol pages
    private var lastSpaceAt = 0L
    private var autoSpaced = false              // we added a space after a picked suggestion
    private var suggestionsAllowed = true
    private var learningAllowed = true

    // ─── prediction ───
    private val predictors = HashMap<Language, WordPredictor>()
    private val histories = HashMap<Language, UserHistory>()
    private val saveHistory = Runnable { persistHistories() }

    // ─── ASR ───
    private var asrEngine: AsrEngine? = null
    private var asrLoad: Deferred<AsrEngine?>? = null
    private val audioRecorder = AudioRecorder()
    private val asrScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var transcribing = false

    // ═══════════════════════════════ lifecycle ═══════════════════════════════

    override fun onCreate() {
        super.onCreate()
        settings = KeyboardSettings(this)
        language = runCatching { Language.valueOf(settings.language) }.getOrDefault(Language.UYGHUR)
        loadDictionaries()
        preloadAsrEngine()
    }

    override fun onCreateInputView(): View {
        val v = KeyboardView(this)
        v.listener = this
        view = v
        applyAppearance()
        refreshLayout()
        return v
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val type = info?.inputType ?: 0
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val password = cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ) || cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val noSuggest = type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        val addressy = cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        )
        suggestionsAllowed = settings.suggestions && !password && !noSuggest && cls == InputType.TYPE_CLASS_TEXT
        learningAllowed = !password && !addressy
        symbolsPage = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME) 1 else 0
        if (!restarting || shift != ShiftState.LOCKED) shift = ShiftState.OFF
        autoSpaced = false
        applyAppearance()
        view?.enterLabel = enterLabel(info)
        autoCapitalize()
        refreshLayout()
        updateSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (audioRecorder.isRecording()) stopDictation()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (transcribing || audioRecorder.isRecording()) return
        if (autoCapitalize()) refreshLayout()
        updateSuggestions()
    }

    override fun onDestroy() {
        main.removeCallbacks(saveHistory)
        persistHistories()
        asrScope.cancel()
        asrEngine?.close()
        asrEngine = null
        super.onDestroy()
    }

    private fun applyAppearance() {
        val v = view ?: return
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        v.theme = if (night) KeyboardTheme.DARK else KeyboardTheme.LIGHT
        v.haptics = settings.vibration
        v.sound = settings.sound
    }

    private fun refreshLayout() {
        val v = view ?: return
        val layout = when {
            symbolsPage > 0 -> Layouts.symbols(symbolsPage, language)
            language == Language.UYGHUR -> Layouts.uyghur(shift, settings.numberRow)
            else -> Layouts.english(shift, settings.numberRow)
        }
        v.setLayout(layout)
        v.shiftState = shift
        v.layoutDirection = View.LAYOUT_DIRECTION_LTR
    }

    private fun enterLabel(info: EditorInfo?): String? {
        info ?: return null
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
        val ug = language == Language.UYGHUR
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> if (ug) "ئىزدە" else "Search"
            EditorInfo.IME_ACTION_SEND -> if (ug) "يوللا" else "Send"
            EditorInfo.IME_ACTION_GO -> if (ug) "ماڭ" else "Go"
            EditorInfo.IME_ACTION_NEXT -> if (ug) "كېيىنكى" else "Next"
            EditorInfo.IME_ACTION_DONE -> if (ug) "تامام" else "Done"
            else -> null
        }
    }

    // ═══════════════════════════════ text editing ═══════════════════════════════

    private fun before(n: Int = 64) = currentInputConnection?.getTextBeforeCursor(n, 0)?.toString().orEmpty()

    private fun isWordChar(c: Char) = c.isLetter() || c == '’' || c == '\''

    /** The word being typed (letters right before the cursor) and the word before it. */
    private fun currentAndPrevious(): Pair<String, String?> {
        val b = before()
        var i = b.length
        while (i > 0 && isWordChar(b[i - 1])) i--
        val cur = b.substring(i)
        var j = i
        while (j > 0 && b[j - 1] == ' ') j--
        if (j > 0 && !isWordChar(b[j - 1])) return cur to null      // punctuation: new sentence context
        var k = j
        while (k > 0 && isWordChar(b[k - 1])) k--
        val prev = b.substring(k, j).takeIf { it.isNotEmpty() && (j < i || cur.isEmpty()) }
        return cur to prev
    }

    override fun onText(text: String) {
        val ic = currentInputConnection ?: return
        when {
            text == " " -> typeSpace()
            text.length == 1 && text[0] in ".,!?؟،؛;:" && autoSpaced && before(1) == " " -> {
                // "word| " + "." → "word. "
                ic.deleteSurroundingText(1, 0)
                learnCurrentWord()
                ic.commitText("$text ", 1)
                autoSpaced = true
            }
            else -> {
                if (text.length == 1 && !isWordChar(text[0])) learnCurrentWord()
                ic.commitText(text, 1)
                autoSpaced = false
                if (shift == ShiftState.ON && text.any { it.isLetter() }) { shift = ShiftState.OFF; refreshLayout() }
            }
        }
        if (text != " ") lastSpaceAt = 0
    }

    private fun typeSpace() {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        val b = before(2)
        if (now - lastSpaceAt < DOUBLE_SPACE_MS && b.length == 2 && b[1] == ' ' && isWordChar(b[0])) {
            // double space → ". " (period shortcut)
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            lastSpaceAt = 0
        } else {
            learnCurrentWord()
            ic.commitText(" ", 1)
            lastSpaceAt = now
        }
        autoSpaced = false
        if (symbolsPage > 0 && currentInputEditorInfo?.inputType?.and(InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT) {
            symbolsPage = 0; refreshLayout()
        }
    }

    override fun onBackspace(wordMode: Boolean) {
        val ic = currentInputConnection ?: return
        autoSpaced = false; lastSpaceAt = 0
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) { ic.commitText("", 1); return }
        val b = before(48)
        if (b.isEmpty()) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); return }
        if (wordMode) {
            var i = b.length
            while (i > 0 && b[i - 1].isWhitespace()) i--
            while (i > 0 && isWordChar(b[i - 1])) i--
            if (i == b.length) i-- // a lone symbol
            ic.deleteSurroundingText(b.length - i, 0)
        } else {
            val n = if (b.length >= 2 && Character.isSurrogatePair(b[b.length - 2], b[b.length - 1])) 2 else 1
            ic.deleteSurroundingText(n, 0)
        }
    }

    override fun onFunctionKey(key: Key) {
        when (key.type) {
            KeyType.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                shift = when (shift) {
                    ShiftState.OFF -> ShiftState.ON
                    ShiftState.ON -> if (now - lastShiftTap < DOUBLE_TAP_SHIFT_MS) ShiftState.LOCKED else ShiftState.OFF
                    ShiftState.LOCKED -> ShiftState.OFF
                }
                lastShiftTap = now
                refreshLayout()
            }
            KeyType.SYMBOLS -> { symbolsPage = 1; refreshLayout() }
            KeyType.ALPHA -> { symbolsPage = 0; refreshLayout() }
            KeyType.SYMBOLS_PAGE -> { symbolsPage = if (symbolsPage == 1) 2 else 1; refreshLayout() }
            KeyType.LANGUAGE -> {
                language = if (language == Language.UYGHUR) Language.ENGLISH else Language.UYGHUR
                settings.language = language.name
                shift = ShiftState.OFF
                view?.enterLabel = enterLabel(currentInputEditorInfo)
                autoCapitalize()
                refreshLayout()
                updateSuggestions()
            }
            KeyType.ENTER -> {
                learnCurrentWord()
                autoSpaced = false
                val info = currentInputEditorInfo
                val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                val noAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
                if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    currentInputConnection?.performEditorAction(action)
                } else {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                }
            }
            else -> {}
        }
    }

    override fun onCursor(delta: Int) {
        sendDownUpKeyEvents(if (delta > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
    }

    override fun onLanguageLongPress() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    /** English: capitalise the first letter of a sentence (if the field asks for it). */
    private fun autoCapitalize(): Boolean {
        if (language != Language.ENGLISH || symbolsPage > 0 || shift == ShiftState.LOCKED) return false
        val info = currentInputEditorInfo ?: return false
        val caps = currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0
        val want = if (caps != 0) ShiftState.ON else ShiftState.OFF
        if (want == shift) return false
        shift = want
        return true
    }

    // ═══════════════════════════════ prediction ═══════════════════════════════

    private fun loadDictionaries() {
        asrScope.launch(Dispatchers.IO) {
            for (lang in Language.values()) {
                val code = if (lang == Language.UYGHUR) "ug" else "en"
                val history = try {
                    File(filesDir, "user_history_$code.txt").takeIf { it.exists() }?.readText()?.let(UserHistory::parse)
                } catch (e: Exception) { null } ?: UserHistory()
                if (lang == Language.UYGHUR) migrateOldDictionary(history)
                try {
                    val lexicon = assets.open(if (lang == Language.UYGHUR) "uyghur_dict.txt" else "english_dict.txt").use { Lexicon.load(it) }
                    val bigrams = if (lang == Language.UYGHUR) assets.open("uyghur_bigrams.txt").use { Bigrams.load(it) } else Bigrams.EMPTY
                    val neighbours = Layouts.neighbours(
                        if (lang == Language.UYGHUR) Layouts.uyghur(ShiftState.OFF, false) else Layouts.english(ShiftState.OFF, false))
                    val p = WordPredictor(
                        lexicon, bigrams, neighbours, history,
                        if (lang == Language.UYGHUR) WordPredictor.UYGHUR_CONFUSABLE else emptyMap(),
                        if (lang == Language.UYGHUR) WordPredictor.UYGHUR_ALPHABET else WordPredictor.ENGLISH_ALPHABET,
                    )
                    withContext(Dispatchers.Main) {
                        histories[lang] = history
                        predictors[lang] = p
                        if (lang == language) updateSuggestions()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Dictionary load failed for $lang", e)
                }
            }
        }
    }

    /** Words learned by version 1.x (SharedPreferences JSON) are kept. */
    private fun migrateOldDictionary(history: UserHistory) {
        val prefs = getSharedPreferences("KeyboardDictPrefs", MODE_PRIVATE)
        val json = prefs.getString("userWords", null) ?: return
        try {
            val o = org.json.JSONObject(json)
            for (k in o.keys()) repeat(o.getInt(k).coerceAtMost(20)) { history.learn(null, k) }
            prefs.edit().clear().apply()
        } catch (e: Exception) { Log.w(TAG, "old dictionary migration failed", e) }
    }

    private fun updateSuggestions() {
        val v = view ?: return
        if (!suggestionsAllowed || symbolsPage > 0) { v.setSuggestions(listOf("", "", "")); return }
        val p = predictors[language] ?: return
        val (cur, prev) = currentAndPrevious()
        if (cur.isEmpty() && before(1).let { it.isNotEmpty() && it[0] != ' ' }) { v.setSuggestions(listOf("", "", "")); return }
        v.setSuggestions(StripSlots.arrange(p.suggest(prev, cur)))
    }

    override fun onSuggestion(text: String) {
        val ic = currentInputConnection ?: return
        val (cur, prev) = currentAndPrevious()
        ic.beginBatchEdit()
        if (cur.isNotEmpty()) ic.deleteSurroundingText(cur.length, 0)
        ic.commitText("$text ", 1)
        ic.endBatchEdit()
        learn(prev, text)
        autoSpaced = true
        lastSpaceAt = 0
        if (shift == ShiftState.ON) { shift = ShiftState.OFF; refreshLayout() }
    }

    private fun learnCurrentWord() {
        val (cur, prev) = currentAndPrevious()
        if (cur.length >= 2) learn(prev, cur)
    }

    private fun learn(prev: String?, word: String) {
        if (!learningAllowed) return
        val h = histories[language] ?: return
        h.learn(prev?.lowercase(), word.lowercase())
        main.removeCallbacks(saveHistory)
        main.postDelayed(saveHistory, 4000)
    }

    private fun persistHistories() {
        val snapshot = histories.filterValues { it.dirty }.mapValues { it.value.serialize() }
        if (snapshot.isEmpty()) return
        asrScope.launch(Dispatchers.IO) {
            for ((lang, text) in snapshot) {
                val code = if (lang == Language.UYGHUR) "ug" else "en"
                try {
                    val tmp = File(filesDir, "user_history_$code.tmp")
                    tmp.writeText(text)
                    tmp.renameTo(File(filesDir, "user_history_$code.txt"))
                } catch (e: Exception) { Log.w(TAG, "saving history failed", e) }
            }
        }
    }

    // ═══════════════════════════════ voice dictation ═══════════════════════════════

    override fun onMicTap() {
        when {
            transcribing -> Unit
            audioRecorder.isRecording() -> stopDictation()
            else -> startDictation()
        }
    }

    private fun preloadAsrEngine(): Deferred<AsrEngine?> {
        asrLoad?.let { if (!it.isCompleted || asrEngine != null) return it }
        val job = asrScope.async(Dispatchers.IO) {
            try {
                AsrEngine(this@UyghurKeyboardService).also { asrEngine = it }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load ASR engine: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@UyghurKeyboardService, "ASR يۈكلەنمىدى: ${e.message}", Toast.LENGTH_LONG).show()
                }
                null
            }
        }
        asrLoad = job
        return job
    }

    private var segmentPrefix: String? = null
    private var composingShown = false

    private fun startDictation() {
        val hasPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            Toast.makeText(this, "مىكروفون ئىجازىتى يوق — «ئۇيغۇرچە كۇنۇپكا تاختىسى» ئەپىدىن ئىجازەت بېرىڭ", Toast.LENGTH_LONG).show()
            return
        }
        if (transcribing) return
        if (asrEngine == null) preloadAsrEngine()
        if (audioRecorder.startRecording()) {
            learnCurrentWord()
            view?.setSuggestions(listOf("", "", ""))
            view?.dictation = KeyboardView.Dictation.LISTENING
            asrScope.launch { dictationLoop() }
        } else {
            Toast.makeText(this, "مىكروفون ئېچىلمىدى", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopDictation() {
        if (!audioRecorder.isRecording()) return
        transcribing = true
        audioRecorder.stop()
        view?.dictation = KeyboardView.Dictation.FINISHING
    }

    /**
     * Background loop while dictating: shows provisional text while you speak and commits each
     * phrase after a ~1 s pause (see SpeechSegmenter).
     */
    private suspend fun dictationLoop() {
        val segmenter = SpeechSegmenter(audioRecorder.recordRate)
        var fed = 0
        var gotText = false
        var engine: AsrEngine? = null
        try {
            engine = asrEngine ?: preloadAsrEngine().await()
            while (true) {
                val stopped = !audioRecorder.isRecording()
                if (audioRecorder.isFull()) withContext(Dispatchers.Main) { stopDictation() }
                val n = audioRecorder.sampleCount
                if (n > fed) { segmenter.feed(audioRecorder.readRaw(fed, n)); fed = n }
                when (val d = segmenter.decide(final = stopped)) {
                    is SpeechSegmenter.Decision.Partial -> {
                        val text = engine?.transcribe(audioRecorder.read(d.startSample, d.endSample))?.arabic.orEmpty()
                        val stable = SpeechSegmenter.stablePartial(text)
                        withContext(Dispatchers.Main) { showPartial(stable) }
                    }
                    is SpeechSegmenter.Decision.Final -> {
                        val text = engine?.transcribe(audioRecorder.read(d.startSample, d.endSample))?.arabic.orEmpty()
                        if (text.isNotEmpty()) gotText = true
                        withContext(Dispatchers.Main) { commitSegment(text) }
                    }
                    SpeechSegmenter.Decision.None -> if (stopped) break else delay(120)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Dictation error", e)
            withContext(Dispatchers.Main) { Toast.makeText(this@UyghurKeyboardService, "ASR خاتالىقى", Toast.LENGTH_SHORT).show() }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                if (audioRecorder.isRecording()) audioRecorder.stop()
                if (composingShown) commitSegment("")
                transcribing = false
                view?.dictation = KeyboardView.Dictation.OFF
                updateSuggestions()
                if (!gotText && engine != null) {
                    Toast.makeText(this@UyghurKeyboardService, "ئاۋاز تونۇلمىدى", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showPartial(text: String) {
        val ic = currentInputConnection ?: return
        if (text.isEmpty() && !composingShown) return
        val prefix = segmentPrefix ?: run {
            val b = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
            (if (b.isNotEmpty() && !b.last().isWhitespace()) " " else "").also { segmentPrefix = it }
        }
        ic.setComposingText(if (text.isEmpty()) "" else prefix + text, 1)
        composingShown = text.isNotEmpty()
    }

    private fun commitSegment(text: String) {
        val ic = currentInputConnection
        if (ic != null) {
            if (text.isNotEmpty()) {
                val prefix = segmentPrefix ?: run {
                    val b = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
                    if (b.isNotEmpty() && !b.last().isWhitespace()) " " else ""
                }
                ic.commitText("$prefix$text ", 1)
            } else if (composingShown) {
                ic.setComposingText("", 1)
                ic.finishComposingText()
            }
        }
        segmentPrefix = null
        composingShown = false
    }
}

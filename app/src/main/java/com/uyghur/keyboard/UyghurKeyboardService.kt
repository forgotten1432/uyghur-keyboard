package com.uyghur.keyboard

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import android.util.Log
import android.util.TypedValue
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import com.uyghur.keyboard.asr.SpeechSegmenter

/**
 * UyghurKeyboardService — The core IME service for the Uyghur keyboard.
 *
 * ═══════════════════════════════════════════════════════════════════
 * ARCHITECTURE OVERVIEW
 * ═══════════════════════════════════════════════════════════════════
 * The keyboard UI is built PROGRAMMATICALLY (not in XML). Each time
 * the keyboard is shown, we:
 *   1. Inflate the container layout (keyboard_view.xml = 4 empty rows)
 *   2. Loop over KeyboardConstants arrays to create Button objects
 *   3. Attach OnClickListener to each button
 *
 * This approach makes it easy to:
 *   • Switch between normal and shifted layers
 *   • Add/remove keys dynamically
 *   • Apply consistent styling in one place
 *
 * ═══════════════════════════════════════════════════════════════════
 * KOTLIN CONCEPT: "var vs val"
 * ═══════════════════════════════════════════════════════════════════
 * • "val" = immutable (like Dart's "final"). Cannot be reassigned.
 * • "var" = mutable. Can be reassigned.
 * Use "val" by default; only use "var" when you need to change the value.
 */
class UyghurKeyboardService : InputMethodService() {

    companion object {
        private const val TAG = "UyghurIME"
    }

    // ─── State ───────────────────────────────────────────────────────
    private var isShifted = false
    private var currentLanguage = KeyboardConstants.KeyboardLanguage.UYGHUR

    // References to the keyboard view and its rows (5 rows).
    private lateinit var keyboardView: View
    private lateinit var row0: LinearLayout  // Numbers (always visible)
    private lateinit var row1: LinearLayout  // Top letter row
    private lateinit var row2: LinearLayout  // Home letter row
    private lateinit var row3: LinearLayout  // Bottom letter row (shift + backspace)
    private lateinit var row4: LinearLayout  // Action row

    // ─── Suggestions ──────────────────────────────────────────────────
    private lateinit var suggestionScroll: android.widget.HorizontalScrollView
    private lateinit var suggestionRow: LinearLayout

    // ─── ASR Components ─────────────────────────────────────────────
    private var asrEngine: AsrEngine? = null
    private var asrLoad: Deferred<AsrEngine?>? = null
    private val audioRecorder = AudioRecorder()
    private var micButton: Button? = null
    private val asrScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var transcribing = false

    // ─── Utilities ──────────────────────────────────────────────────
    // Precompiled regex for fluid, instant typing performance
    private val wordMatcherRegex = Regex("""[a-zA-ZğüşıöçĞÜŞİÖÇئ-ى]+$""")
    private val allWordsRegex = Regex("""([a-zA-ZğüşıöçĞÜŞİÖÇئ-ى]+)""")

    // ═══════════════════════════════════════════════════════════════
    // LIFECYCLE METHODS
    // ═══════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Uyghur Keyboard Service created")
        
        // Load user-learned personalized dictionaries and bigrams
        DictionaryHelper.init(this)

        // Asynchronously fetch extra vocabulary from the internet 
        // without blocking the keyboard. You can replace the URL with your own database endpoint.
        CoroutineScope(Dispatchers.IO).launch {
            DictionaryHelper.fetchUyghurWordsFromUrl("https://raw.githubusercontent.com/forgotten1432/uyghur-keyboard/main/uyghur_words.txt")
        }

        // Asynchronously preload ASR engine so it's ready for immediate use
        preloadAsrEngine()
    }

    /**
     * Called when the system needs the keyboard UI.
     * We inflate the container, find the row views, and populate with keys.
     *
     * ═══════════════════════════════════════════════════════════════
     * KOTLIN CONCEPT: "findViewById<Type>(R.id.name)"
     * ═══════════════════════════════════════════════════════════════
     * Finds a View in the inflated layout by its XML id.
     * The <Type> in angle brackets is a GENERIC TYPE PARAMETER —
     * it tells the compiler what type of View to return.
     * Similar to Dart's generic syntax: List<String>.
     */
    override fun onCreateInputView(): View {
        Log.d(TAG, "onCreateInputView: Building keyboard")

        keyboardView = layoutInflater.inflate(R.layout.keyboard_view, null)

        // Find the 5 row containers
        row0 = keyboardView.findViewById(R.id.row0)
        row1 = keyboardView.findViewById(R.id.row1)
        row2 = keyboardView.findViewById(R.id.row2)
        row3 = keyboardView.findViewById(R.id.row3)
        row4 = keyboardView.findViewById(R.id.row4)
        suggestionScroll = keyboardView.findViewById(R.id.suggestionScroll)
        suggestionRow = keyboardView.findViewById(R.id.suggestionRow)

        // Populate all rows with keys
        populateKeys()

        return keyboardView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView: Keyboard shown (restarting=$restarting)")
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // keyboard hidden while dictating: stop the microphone (the last phrase is still committed)
        if (audioRecorder.isRecording()) stopDictation()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        Log.d(TAG, "onFinishInput: Keyboard hidden")
        // Reset shift state when leaving a text field
        if (isShifted) {
            isShifted = false
            populateKeys()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Service destroyed")
        asrScope.cancel()
        asrEngine?.close()
        asrEngine = null
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════════
    // KEY POPULATION
    // ═══════════════════════════════════════════════════════════════

    /**
     * Clears all rows and repopulates them based on the current layer.
     *
     * ═══════════════════════════════════════════════════════════════
     * KOTLIN CONCEPT: "if (condition) expr1 else expr2" as expression
     * ═══════════════════════════════════════════════════════════════
     * In Kotlin, "if" is an EXPRESSION (returns a value), not just a
     * statement. So you can write:
     *     val chars = if (isShifted) ROW_1_SHIFTED else ROW_1
     * This is similar to Dart's ternary: isShifted ? shifted : normal
     */
    private fun populateKeys() {
        // Clear existing keys from all rows
        row0.removeAllViews()
        row1.removeAllViews()
        row2.removeAllViews()
        row3.removeAllViews()
        row4.removeAllViews()

        // ─── Row 0: Number row (always visible) ─────────────────────
        val numbers = KeyboardConstants.ROW_1_SHIFTED  // 1-0
        for (char in numbers) {
            row0.addView(createNumberKey(char, 1f))
        }

        if (isShifted) {
            // ─── Shifted: Symbols layout ─────────────────────────────
            val sr2 = KeyboardConstants.ROW_2_SHIFTED
            val sr3 = KeyboardConstants.ROW_3_SHIFTED

            for (char in sr2) {
                row1.addView(createCharKey(char, 1f))
            }
            for (char in sr3) {
                row2.addView(createCharKey(char, 1f))
            }
            // Row 3 empty for symbols — just shift + backspace
            row3.addView(createSpecialKey("⇧", 1.5f) { onShiftPressed() })
            row3.addView(createBackspaceKey(1.5f))
        } else {
            // ─── Normal: Dynamic language layout ───────────────────────
            val (row1Chars, row2Chars, row3Chars) = when (currentLanguage) {
                KeyboardConstants.KeyboardLanguage.UYGHUR -> Triple(KeyboardConstants.UYGHUR_ROW_1, KeyboardConstants.UYGHUR_ROW_2, KeyboardConstants.UYGHUR_ROW_3)
                KeyboardConstants.KeyboardLanguage.ENGLISH -> Triple(KeyboardConstants.EN_ROW_1, KeyboardConstants.EN_ROW_2, KeyboardConstants.EN_ROW_3)
            }

            for (char in row1Chars) {
                row1.addView(createCharKey(char, 1f))
            }

            // Other languages (like English) may use half-key spacer for visual alignment if they have 9 keys, but Uyghur has 9 keys on home row too.
            val needsSpacer = currentLanguage == KeyboardConstants.KeyboardLanguage.ENGLISH
            if (needsSpacer) {
                row2.addView(android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dpToPx(46), 0.5f) })
            }
            
            for (char in row2Chars) {
                row2.addView(createCharKey(char, 1f))
            }

            if (needsSpacer) {
                row2.addView(android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dpToPx(46), 0.5f) })
            }

            // Row 3: Shift + bottom chars + Backspace
            row3.addView(createSpecialKey("⇧", 1.3f) { onShiftPressed() })
            for (char in row3Chars) {
                row3.addView(createCharKey(char, 1f))
            }
            row3.addView(createBackspaceKey(1.3f))
        }

        // ─── Row 4: !#1 + Globe + comma + spacebar + mic + period + enter ────
        row4.addView(createSpecialKey("!#1", 1.2f) { onShiftPressed() })
        row4.addView(createSpecialKey("\uD83C\uDF10", 1.0f) { onGlobePressed() })
        row4.addView(createCharKey('،', 1f))
        row4.addView(createSpacebar(3.6f))
        row4.addView(createMicKey(1.0f))
        row4.addView(createCharKey('.', 1f))
        row4.addView(createSpecialKey("↵", 1.2f) { onEnterPressed() })
    }

    // ═══════════════════════════════════════════════════════════════
    // KEY FACTORY METHODS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Creates a character key button that types its character when pressed.
     *
     * ═══════════════════════════════════════════════════════════════
     * KOTLIN CONCEPT: "apply { ... }" — Scope Function
     * ═══════════════════════════════════════════════════════════════
     * "apply" is a scope function that lets you configure an object
     * inline. Inside the block, "this" refers to the object.
     *
     * Instead of:
     *     val btn = Button(this)
     *     btn.text = "A"
     *     btn.textSize = 18f
     *     btn.setOnClickListener { ... }
     *
     * You write:
     *     Button(this).apply {
     *         text = "A"
     *         textSize = 18f
     *         setOnClickListener { ... }
     *     }
     *
     * Similar to Dart's cascade operator (..)
     *
     * @param char   The character this key types
     * @param weight The layout weight (flex factor) for proportional sizing
     */
    private fun createCharKey(char: Char, weight: Float): android.view.View {
        return android.widget.FrameLayout(this).apply {
            setBackgroundResource(R.drawable.key_background)
            layoutParams = LinearLayout.LayoutParams(
                0, dpToPx(46), weight
            ).apply {
                setMargins(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))
            }

            // Primary Character
            val primaryText = android.widget.TextView(this@UyghurKeyboardService).apply {
                text = char.toString()
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            addView(primaryText)

            // Secondary (Long Press) Character Hint
            val longPressMap = when (currentLanguage) {
                KeyboardConstants.KeyboardLanguage.UYGHUR -> KeyboardConstants.UYGHUR_LONG_PRESS
                KeyboardConstants.KeyboardLanguage.ENGLISH -> KeyboardConstants.EN_LONG_PRESS
            }
            val longPressChar = longPressMap[char]

            if (longPressChar != null) {
                val secText = android.widget.TextView(this@UyghurKeyboardService).apply {
                    text = longPressChar.toString()
                    setTextColor(0xFFAAAAAA.toInt()) // Gray hint
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = android.view.Gravity.TOP or android.view.Gravity.END
                        setMargins(0, dpToPx(3), dpToPx(4), 0)
                    }
                }
                addView(secText)
            }

            setOnTouchListener { view, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                false // Allow onClick and onLongClick to trigger normally
            }

            setOnClickListener { view ->
                onCharPressed(char)
            }

            setOnLongClickListener { view ->
                if (longPressChar != null) {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onCharPressed(longPressChar)
                    true
                } else {
                    false
                }
            }

            contentDescription = char.toString()
            isClickable = true
            isFocusable = true
        }
    }

    /**
     * Creates a number key — slightly shorter height, different background.
     */
    private fun createNumberKey(char: Char, weight: Float): Button {
        return Button(this).apply {
            text = char.toString()
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setBackgroundResource(R.drawable.key_background_number)
            isAllCaps = false

            layoutParams = LinearLayout.LayoutParams(
                0, dpToPx(38), weight
            ).apply {
                setMargins(dpToPx(2), dpToPx(1), dpToPx(2), dpToPx(1))
            }

            setOnTouchListener { view, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                false
            }

            setOnClickListener { view ->
                onCharPressed(char)
            }

            contentDescription = char.toString()
        }
    }

    /**
     * Creates a special key (shift, backspace, enter) with a custom action.
     *
     * ═══════════════════════════════════════════════════════════════
     * KOTLIN CONCEPT: "() -> Unit" — Lambda / Function Type
     * ═══════════════════════════════════════════════════════════════
     * "() -> Unit" is a function type: a function that takes no
     * parameters and returns nothing (Unit = void).
     *
     * When we write:
     *     createSpecialKey("⇧", 1.3f) { onShiftPressed() }
     *
     * The last argument { onShiftPressed() } is a LAMBDA — an anonymous
     * function passed as an argument. In Kotlin, if the last parameter
     * is a function, you can write the lambda OUTSIDE the parentheses.
     * This is called "trailing lambda syntax".
     *
     * Similar to Dart's: createSpecialKey("⇧", 1.3, () => onShiftPressed())
     */
    private fun createSpecialKey(label: String, weight: Float, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setBackgroundResource(R.drawable.key_background_special)
            isAllCaps = false

            layoutParams = LinearLayout.LayoutParams(
                0, dpToPx(46), weight
            ).apply {
                setMargins(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))
            }

            setOnTouchListener { view, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                false
            }

            setOnClickListener { view ->
                action()
            }
        }
    }

    /**
     * Creates the backspace key which repeats when held down.
     */
    private fun createBackspaceKey(weight: Float): Button {
        return Button(this).apply {
            text = "⌫"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setBackgroundResource(R.drawable.key_background_special)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(46), weight).apply {
                setMargins(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))
            }
            contentDescription = "Backspace"

            var handler: android.os.Handler? = null
            var runnable: Runnable? = null

            setOnTouchListener { view, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onBackspacePressed()
                        
                        handler = android.os.Handler(android.os.Looper.getMainLooper())
                        runnable = object : Runnable {
                            override fun run() {
                                onBackspacePressed()
                                handler?.postDelayed(this, 50)  // Repeat every 50ms
                            }
                        }
                        handler?.postDelayed(runnable!!, 400) // Start repeating after 400ms delay
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        handler?.removeCallbacksAndMessages(null)
                        handler = null
                        runnable = null
                        view.performClick() // Register click for accessibility
                        true
                    }
                    else -> false
                }
            }
        }
    }

    /**
     * Creates the microphone button with accent styling.
     * Stores a reference in [micButton] so we can update its label
     * between 🎤 (idle) and ⏹ (recording).
     */
    private fun createMicKey(weight: Float): Button {
        return Button(this).apply {
            text = when {
                transcribing -> "⏳"
                audioRecorder.isRecording() -> "⏹"
                else -> "🎤"
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setBackgroundResource(R.drawable.key_background_special)
            isAllCaps = false

            layoutParams = LinearLayout.LayoutParams(
                0, dpToPx(46), weight
            ).apply {
                setMargins(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))
            }

            contentDescription = getString(R.string.key_microphone)

            // Tap to start, tap again to stop (text appears while you speak).
            setOnClickListener { view ->
                try {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    toggleDictation()
                } catch (e: Exception) {
                    Log.e(TAG, "Mic tap error", e)
                }
            }

            micButton = this
        }
    }

    /**
     * Creates the spacebar with wider proportional width.
     */
    private fun createSpacebar(weight: Float): Button {
        return Button(this).apply {
            text = currentLanguage.label
            setTextColor(0xFF999999.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setBackgroundResource(R.drawable.key_background_spacebar)
            isAllCaps = false

            layoutParams = LinearLayout.LayoutParams(
                0, dpToPx(46), weight
            ).apply {
                setMargins(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))
            }

            contentDescription = getString(R.string.key_spacebar)

            setOnTouchListener { view, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                false
            }

            setOnClickListener { view ->
                onCharPressed(' ')
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // INPUT ACTIONS
    // ═══════════════════════════════════════════════════════════════
    // These methods interact with the "InputConnection" — the bridge
    // between your keyboard and whatever app the user is typing in.
    //
    // currentInputConnection is a property inherited from InputMethodService.
    // It's similar to a TextEditingController in Flutter, but works
    // across ALL apps, not just your own.
    // ═══════════════════════════════════════════════════════════════

    /**
     * Types a character at the current cursor position.
     *
     * commitText(text, cursorPosition):
     *   • text = the string to insert
     *   • cursorPosition = where to place the cursor after insertion
     *     (1 = after the inserted text, which is the normal behavior)
     */
    private fun onCharPressed(char: Char) {
        val ic = currentInputConnection
        ic?.commitText(char.toString(), 1)
        
        // If a word boundary is typed, learn the word and bigram pattern silently
        if (char == ' ' || char == '\n' || char == '.' || char == ',' || char == '!') {
            val textBefore = ic?.getTextBeforeCursor(60, 0)?.toString() ?: ""
            val matches = allWordsRegex.findAll(textBefore).toList()
            if (matches.isNotEmpty()) {
                val lastWord = matches.last().value
                DictionaryHelper.learnWord(this, lastWord)
                if (matches.size >= 2) {
                    val prevword = matches[matches.size - 2].value
                    DictionaryHelper.learnSequence(this, prevword, lastWord)
                }
            }
        }

        updateSuggestions()
    }

    /**
     * Deletes one character before the cursor.
     *
     * deleteSurroundingText(before, after):
     *   • before = number of chars to delete BEFORE the cursor
     *   • after = number of chars to delete AFTER the cursor
     */
    private fun onBackspacePressed() {
        currentInputConnection?.deleteSurroundingText(1, 0)
        updateSuggestions()
    }

    /**
     * Reads the word currently being typed and updates the suggestion bar above the keyboard.
     */
    private fun updateSuggestions() {
        val ic = currentInputConnection ?: return
        val textBefore = ic.getTextBeforeCursor(60, 0)?.toString() ?: ""

        val lastWordMatch = wordMatcherRegex.find(textBefore)
        val currentWord = lastWordMatch?.value ?: ""

        // Find the previous word for Next-Word Prediction
        val matches = allWordsRegex.findAll(textBefore).toList()
        var previousWord = ""
        if (currentWord.isNotBlank() && matches.size >= 2) {
            previousWord = matches[matches.size - 2].value
        } else if (currentWord.isBlank() && matches.isNotEmpty() && textBefore.takeLast(1).matches(Regex("""[\s\p{Punct}]"""))) {
            previousWord = matches.last().value
        }

        val suggestions = DictionaryHelper.getSuggestions(previousWord, currentWord, currentLanguage)

        if (suggestions.isEmpty()) {
            suggestionRow.removeAllViews()
            return
        }

        suggestionRow.removeAllViews()

        for (word in suggestions) {
            val btn = Button(this).apply {
                text = word
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    setMargins(dpToPx(8), 0, dpToPx(8), 0)
                }

                setOnClickListener {
                    view -> view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    
                    // Learn this selection securely
                    DictionaryHelper.learnWord(this@UyghurKeyboardService, word)
                    if (previousWord.isNotBlank()) {
                        DictionaryHelper.learnSequence(this@UyghurKeyboardService, previousWord, word)
                    }

                    // Delete the currently typed partial word
                    ic.deleteSurroundingText(currentWord.length, 0)
                    // Paste the completed word + a space
                    ic.commitText("$word ", 1)

                    updateSuggestions()
                }
            }
            suggestionRow.addView(btn)
        }
    }

    /**
     * Sends an Enter key event to the focused app.
     *
     * We use sendDownUpKeyEvents() instead of commitText("\n") because
     * some apps handle Enter specially (e.g., search forms, chat apps
     * that send messages on Enter).
     */
    private fun onEnterPressed() {
        sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    /**
     * Toggles between the primary (Uyghur characters) and shifted
     * (numbers/symbols) keyboard layer.
     */
    private fun onShiftPressed() {
        isShifted = !isShifted
        populateKeys()  // Rebuild all rows with the new character set
        Log.d(TAG, "Shift toggled: isShifted=$isShifted")
    }

    /**
     * Cycles to the next available language interface.
     */
    private fun onGlobePressed() {
        val langs = KeyboardConstants.KeyboardLanguage.values()
        val nextOrdinal = (currentLanguage.ordinal + 1) % langs.size
        currentLanguage = langs[nextOrdinal]
        populateKeys()
        Log.d(TAG, "Language switched to: ${currentLanguage.label}")
    }

    /**
     * Microphone button handler — toggles between recording and transcribing.
     * First tap: starts recording (mic button shows ⏹).
     * Second tap: stops recording, runs ONNX inference, commits transcribed text.
     */
    /**
     * Preloads the ASR engine asynchronously. 
     */
    private fun preloadAsrEngine(): Deferred<AsrEngine?> {
        asrLoad?.let { if (!it.isCompleted || asrEngine != null) return it }
        Log.d(TAG, "preloadAsrEngine: starting async load...")
        val job = asrScope.async(Dispatchers.IO) {
            try {
                AsrEngine(this@UyghurKeyboardService).also {
                    asrEngine = it
                    Log.d(TAG, "ASR engine preloaded successfully ✅")
                }
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

    // ═══════════════════════════════════════════════════════════════
    // LIVE DICTATION  (tap 🎤 to start, tap ⏹ to stop)
    // ═══════════════════════════════════════════════════════════════

    private var dictationJob: Job? = null
    private var segmentPrefix: String? = null      // " " or "" for the segment being shown
    private var composingShown = false

    private fun toggleDictation() {
        when {
            transcribing -> Unit                                  // finishing the last words
            audioRecorder.isRecording() -> stopDictation()
            else -> startDictation()
        }
    }

    private fun startDictation() {
        Log.d(TAG, "startDictation called")
        // Check RECORD_AUDIO permission
        val hasPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "RECORD_AUDIO permission granted: $hasPerm")
        if (!hasPerm) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            Toast.makeText(this, "ئاۋاز خاتىرىلەش ھوقۇقى بېرىلمىگەن. تېلېفون تەڭشەكلىرىدىن ئىجازەت بېرىڭ.", Toast.LENGTH_LONG).show()
            return
        }

        if (transcribing) return

        // Make sure the engine is (being) loaded; recording can start meanwhile.
        if (asrEngine == null) preloadAsrEngine()

        if (!audioRecorder.isRecording()) {
            // ─── Start recording ────────────────────────────────────
            Log.d(TAG, "🎤 Starting recording...")
            val started = audioRecorder.startRecording()
            Log.d(TAG, "🎤 Recording started: $started")
            if (started) {
                updateMicButton(true)
                dictationJob = asrScope.launch { dictationLoop() }
            } else {
                Toast.makeText(this, "Mic ئاچىلالمىدى", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Tap ⏹: microphone off; the loop finishes the last segment, then the button returns to 🎤. */
    private fun stopDictation() {
        if (!audioRecorder.isRecording()) return
        Log.d(TAG, "🎤 Stopping dictation...")
        transcribing = true
        audioRecorder.stop()
        updateMicButton(false)
    }

    /**
     * Runs on a background thread while dictating: feeds new audio to the segmenter, shows
     * provisional text while you speak and commits each phrase when you pause.
     * Recognition calls are sequential, so a slow phone simply updates less often.
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
                        withContext(Dispatchers.Main) { showPartial(text) }
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
            withContext(Dispatchers.Main) {
                Toast.makeText(this@UyghurKeyboardService, "ASR خاتالىقى", Toast.LENGTH_SHORT).show()
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                if (audioRecorder.isRecording()) audioRecorder.stop()
                if (composingShown) commitSegment("")
                transcribing = false
                updateMicButton(false)
                if (!gotText && engine != null) {
                    Toast.makeText(this@UyghurKeyboardService, "ئاۋاز تونۇلمىدى", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    /** Provisional (underlined) text for the phrase being spoken. */
    private fun showPartial(text: String) {
        val ic = currentInputConnection ?: return
        if (text.isEmpty() && !composingShown) return
        val prefix = segmentPrefix ?: run {
            val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
            (if (before.isNotEmpty() && !before.last().isWhitespace()) " " else "").also { segmentPrefix = it }
        }
        ic.setComposingText(if (text.isEmpty()) "" else prefix + text, 1)
        composingShown = text.isNotEmpty()
    }

    /** Final text for a phrase: replaces the provisional text and moves on. */
    private fun commitSegment(text: String) {
        val ic = currentInputConnection
        if (ic != null) {
            if (text.isNotEmpty()) {
                val prefix = segmentPrefix ?: run {
                    val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
                    if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
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

    /**
     * Updates the mic button's visual state to reflect recording status.
     */
    private fun updateMicButton(recording: Boolean) {
        micButton?.text = when {
            transcribing -> "⏳"
            recording -> "⏹"
            else -> "🎤"
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════

    /**
     * Converts density-independent pixels (dp) to actual pixels (px).
     *
     * ═══════════════════════════════════════════════════════════════
     * KOTLIN CONCEPT: Extension-style utility
     * ═══════════════════════════════════════════════════════════════
     * In Android, layout dimensions must be specified in pixels (px),
     * but dp (density-independent pixels) ensures consistent sizing
     * across devices with different screen densities.
     *
     * Samsung Galaxy S23 Ultra has ~600 dpi, so 1dp ≈ 3.75px
     * A budget phone at ~160 dpi has 1dp = 1px
     *
     * This is similar to Flutter's logical pixels.
     */
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}

package com.uyghur.keyboard

import android.content.Context
import android.provider.Settings

/** User settings (shared by the keyboard service and the setup screen). */
class KeyboardSettings(context: Context) {
    private val prefs = context.getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE)
    private val systemSounds = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 0) == 1
    } catch (e: Exception) { false }

    var numberRow: Boolean
        get() = prefs.getBoolean("number_row", false)
        set(v) = prefs.edit().putBoolean("number_row", v).apply()

    var sound: Boolean
        get() = prefs.getBoolean("sound", systemSounds)
        set(v) = prefs.edit().putBoolean("sound", v).apply()

    var vibration: Boolean
        get() = prefs.getBoolean("vibration", true)
        set(v) = prefs.edit().putBoolean("vibration", v).apply()

    var suggestions: Boolean
        get() = prefs.getBoolean("suggestions", true)
        set(v) = prefs.edit().putBoolean("suggestions", v).apply()

    var turkish: Boolean
        get() = prefs.getBoolean("lang_tr", true)
        set(v) = prefs.edit().putBoolean("lang_tr", v).apply()

    var english: Boolean
        get() = prefs.getBoolean("lang_en", true)
        set(v) = prefs.edit().putBoolean("lang_en", v).apply()

    /** Languages the 🌐 key cycles through (Uyghur is always on). */
    fun enabledLanguages(): List<com.uyghur.keyboard.keyboard.Language> = buildList {
        add(com.uyghur.keyboard.keyboard.Language.UYGHUR)
        if (turkish) add(com.uyghur.keyboard.keyboard.Language.TURKISH)
        if (english) add(com.uyghur.keyboard.keyboard.Language.ENGLISH)
    }

    /** Last used language (so the keyboard opens in the same language). */
    var language: String
        get() = prefs.getString("language", "UYGHUR") ?: "UYGHUR"
        set(v) = prefs.edit().putString("language", v).apply()
}

package com.uyghur.keyboard.keyboard

/** Colours for the keyboard (ARGB). Light/dark follow the phone's dark-mode setting. */
data class KeyboardTheme(
    val background: Int,
    val key: Int,
    val functionKey: Int,
    val keyPressed: Int,
    val text: Int,
    val hint: Int,
    val accent: Int,
    val accentText: Int,
    val divider: Int,
    val bubble: Int,
    val shadow: Int,
    val recording: Int,
) {
    companion object {
        val LIGHT = KeyboardTheme(
            background = 0xFFF1F2F5.toInt(),
            key = 0xFFFFFFFF.toInt(),
            functionKey = 0xFFDCDEE4.toInt(),
            keyPressed = 0xFFC6C9D1.toInt(),
            text = 0xFF1B1C1F.toInt(),
            hint = 0xFF8B8E96.toInt(),
            accent = 0xFF3D7BF5.toInt(),
            accentText = 0xFFFFFFFF.toInt(),
            divider = 0x26000000,
            bubble = 0xFFFFFFFF.toInt(),
            shadow = 0x40000000,
            recording = 0xFFE53935.toInt(),
        )
        val DARK = KeyboardTheme(
            background = 0xFF141416.toInt(),
            key = 0xFF38393D.toInt(),
            functionKey = 0xFF27282B.toInt(),
            keyPressed = 0xFF505157.toInt(),
            text = 0xFFF2F2F5.toInt(),
            hint = 0xFF8E9097.toInt(),
            accent = 0xFF5B8DFF.toInt(),
            accentText = 0xFFFFFFFF.toInt(),
            divider = 0x33FFFFFF,
            bubble = 0xFF4A4B50.toInt(),
            shadow = 0x66000000,
            recording = 0xFFFF5252.toInt(),
        )
    }
}

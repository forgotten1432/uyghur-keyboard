package com.uyghur.keyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Setup + settings screen (shown in the app drawer):
 *  1. enable the keyboard, 2. choose it, 3. allow the microphone, then a few settings and a test field.
 */
class SetupActivity : AppCompatActivity() {

    private val micRequest = 100
    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val bg get() = if (dark) 0xFF141416.toInt() else 0xFFF1F2F5.toInt()
    private val fg get() = if (dark) 0xFFF2F2F5.toInt() else 0xFF1B1C1F.toInt()
    private val sub get() = if (dark) 0xFF9A9CA3.toInt() else 0xFF6B6E76.toInt()
    private val accent = 0xFF3D7BF5.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) build()     // refresh step status after returning from system settings / picker
    }

    private fun build() {
        val settings = KeyboardSettings(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(36), dp(22), dp(28))
            setBackgroundColor(bg)
        }
        col.addView(text("ئۇيغۇرچە كۇنۇپكا تاختىسى", 26f, fg).apply { gravity = Gravity.CENTER })
        col.addView(text("Uygurca klavye · sesle yazma", 14f, sub).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(22)) })

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(packageName) == true
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        col.addView(step("1", "كۇنۇپكا تاختىسىنى قوزغىتىش · Klavyeyi etkinleştir", enabled) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        col.addView(step("2", "كۇنۇپكا تاختىسىنى تاللاش · Klavyeyi seç", selected) { imm.showInputMethodPicker() })
        col.addView(step("3", "مىكروفون ئىجازىتى · Mikrofon izni", mic) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), micRequest)
        })

        col.addView(text("تەڭشەكلەر · Ayarlar", 16f, accent).apply { setPadding(0, dp(24), 0, dp(6)) })
        col.addView(toggle("سان قاتارى · Sayı satırı", settings.numberRow) { settings.numberRow = it })
        col.addView(toggle("كۇنۇپكا ئاۋازى · Tuş sesi", settings.sound) { settings.sound = it })
        col.addView(toggle("تىترەش · Titreşim", settings.vibration) { settings.vibration = it })
        col.addView(toggle("سۆز تەۋسىيەسى · Kelime önerileri", settings.suggestions) { settings.suggestions = it })

        col.addView(text("سىناپ بېقىڭ · Burada dene", 16f, accent).apply { setPadding(0, dp(24), 0, dp(6)) })
        col.addView(EditText(this).apply {
            hint = "بۇ يەرگە يېزىڭ…"
            setTextColor(fg); setHintTextColor(sub)
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(col) })
    }

    private fun text(s: String, size: Float, color: Int) = TextView(this).apply {
        text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    }

    private fun step(n: String, label: String, done: Boolean, action: () -> Unit) = Button(this).apply {
        text = (if (done) "✓  " else "$n.  ") + label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(if (done) 0xFF2E7D32.toInt() else 0xFFFFFFFF.toInt())
        setBackgroundColor(if (done) (if (dark) 0xFF1E3A24.toInt() else 0xFFDDF2E0.toInt()) else accent)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(5), 0, dp(5)) }
        setOnClickListener { action() }
    }

    private fun toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) = SwitchCompat(this).apply {
        text = label
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        isChecked = value
        setPadding(0, dp(10), 0, dp(10))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == micRequest && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "مىكروفون ئىجازىتى بېرىلمىدى", Toast.LENGTH_SHORT).show()
        }
        build()
    }
}

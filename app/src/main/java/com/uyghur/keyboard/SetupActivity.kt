package com.uyghur.keyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class SetupActivity : AppCompatActivity() {

    private val RECORD_AUDIO_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 120, 60, 60)
            setBackgroundColor(0xFF1B1B1F.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // App Title
        val title = TextView(this).apply {
            text = "ئۇيغۇر كونوپكا تاختىسى"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        layout.addView(title)

        // Subtitle
        val subtitle = TextView(this).apply {
            text = "تۆۋەندىكى ئىككى قەدەمنى تاماملاڭ"
            setTextColor(0xFFAAAAAA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 50)
        }
        layout.addView(subtitle)

        // ─── Step 1: Activate keyboard ───
        val step1Title = TextView(this).apply {
            text = "1-قەدەم"
            setTextColor(0xFF9E9EFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, 8)
        }
        layout.addView(step1Title)

        val enableBtn = Button(this).apply {
            text = "كونوپكا تاختىسىنى قوزغىتىڭ"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF3A3A5A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isAllCaps = false
            setPadding(40, 28, 40, 28)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 30)
            }
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        }
        layout.addView(enableBtn)

        // ─── Step 2: Open keyboard settings ───
        val step2Title = TextView(this).apply {
            text = "2-قەدەم"
            setTextColor(0xFF9E9EFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, 8)
        }
        layout.addView(step2Title)

        val settingsBtn = Button(this).apply {
            text = "كونوپكا تاختىسى تەڭشەكلىرىنى ئېچىش"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF3A3A5A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isAllCaps = false
            setPadding(40, 28, 40, 28)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 30)
            }
            setOnClickListener {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                startActivity(intent)
            }
        }
        layout.addView(settingsBtn)

        // ─── Step 3: Mic Permission ───
        val step3Title = TextView(this).apply {
            text = "3-قەدەم"
            setTextColor(0xFF9E9EFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, 8)
        }
        layout.addView(step3Title)

        val micBtn = Button(this).apply {
            text = "مىكروفون ئىجازىتى بېرىش 🎤"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF3A3A5A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isAllCaps = false
            setPadding(40, 28, 40, 28)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 40)
            }
            setOnClickListener {
                requestMicPermission()
            }
        }
        layout.addView(micBtn)

        // ─── Permission Status ───
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val statusText = TextView(this).apply {
            text = if (hasMic) "✅ مىكروفون ئىجازىتى بېرىلدى" else "❌ مىكروفون ئىجازىتى بېرىلمىگەن"
            setTextColor(if (hasMic) 0xFF4CAF50.toInt() else 0xFFFF5252.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 0)
        }
        layout.addView(statusText)

        setContentView(layout)
    }

    private fun requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST_CODE)
        } else {
            Toast.makeText(this, "✅ ئىجازەت ئاللىبۇرۇن بېرىلگەن!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "✅ مىكروفون ئىجازىتى بېرىلدى!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "❌ ئىجازەت رەت قىلىندى", Toast.LENGTH_SHORT).show()
            }
            recreate()
        }
    }
}

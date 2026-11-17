package com.uyghur.keyboard.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The whole keyboard drawn by one View: suggestion strip, keys, key preview bubble and the
 * long-press popup. Drawing everything in one view (instead of one Button per key) gives:
 *  - no dead zones: every touch goes to the nearest key,
 *  - fast typing with two thumbs (a new finger commits the previous key immediately),
 *  - Samsung/Gboard-like preview bubble and long-press alternatives.
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onText(text: String)
        fun onFunctionKey(key: Key)
        fun onBackspace(wordMode: Boolean)
        fun onCursor(delta: Int)
        fun onSuggestion(text: String)
        fun onMicTap()
        fun onLanguageLongPress()
    }

    enum class Dictation { OFF, LISTENING, FINISHING }

    var listener: Listener? = null
    var theme: KeyboardTheme = KeyboardTheme.LIGHT
        set(v) { field = v; invalidate() }
    var shiftState = ShiftState.OFF
        set(v) { field = v; invalidate() }
    /** Text for the enter key (e.g. "ئىزدە", "Send"); null = return arrow. */
    var enterLabel: String? = null
        set(v) { field = v; invalidate() }
    var dictation = Dictation.OFF
        set(v) { field = v; invalidate() }
    var dictationText: String = ""
        set(v) { field = v; invalidate() }
    var haptics = true
    var sound = false

    private var layout: Layout = Layouts.english(ShiftState.OFF, false)
    private var geometry: KeyboardGeometry? = null
    private var slots: List<String> = listOf("", "", "")

    // ─── dimensions ───
    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val stripH = 46 * dp
    private val rowH = 54 * dp
    private val bottomPad = 6 * dp
    private val gapX = 2.6f * dp
    private val gapY = 4f * dp
    private val radius = 8f * dp
    private val micW = 54 * dp

    // ─── paints ───
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    // ─── touch state ───
    private class Pointer(val id: Int, var box: KeyBox?, val downX: Float, val downY: Float) {
        var consumed = false
        var popup: List<String> = emptyList()
        var popupSel = 0
        var popupRect = RectF()
        var cursorMode = false
        var lastCursorX = 0f
        var longFired = false
        var stripTarget = -2      // -1 = mic, 0..2 = slot, -2 = not in strip
        var repeat: Runnable? = null
        var longPress: Runnable? = null
    }
    private val pointers = LinkedHashMap<Int, Pointer>()
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var pendingDebug: Pair<String, Boolean>? = null

    fun setLayout(l: Layout) {
        if (l == layout && geometry != null) return
        cancelAll()
        layout = l
        geometry = null
        requestLayout()
        invalidate()
    }

    fun setSuggestions(s: List<String>) {
        if (s == slots) return
        slots = s; invalidate()
    }

    // ═══════════════════════════════ measure ═══════════════════════════════

    private fun keyboardHeight() = stripH + rowH * layout.rows.size + bottomPad

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, keyboardHeight().toInt())
    }

    private fun geo(): KeyboardGeometry {
        val g = geometry
        if (g != null && g.width == width.toFloat()) return g
        return KeyboardGeometry(layout, width.toFloat(), stripH, rowH).also { geometry = it }
    }

    // ═══════════════════════════════ drawing ═══════════════════════════════

    override fun onDraw(canvas: Canvas) {
        pendingDebug?.let { (o, pop) -> if (width > 0) { pendingDebug = null; applyDebugPress(o, pop) } }
        val t = theme
        canvas.drawColor(t.background)
        drawStrip(canvas)
        val g = geo()
        val pressed = pointers.values.mapNotNull { if (it.popup.isEmpty() && !it.cursorMode) it.box?.key else null }.toSet()
        for (b in g.boxes) drawKey(canvas, g, b, b.key in pressed || pointers.values.any { it.cursorMode && it.box === b })
        for (p in pointers.values) {
            val b = p.box ?: continue
            if (p.popup.isNotEmpty()) drawPopup(canvas, p)
            else if (b.key.type == KeyType.CHAR && !p.consumed) drawPreview(canvas, g, b)
        }
    }

    private fun drawStrip(c: Canvas) {
        val t = theme
        val cy = stripH / 2
        // mic button
        val mx = micW / 2
        if (dictation != Dictation.OFF) {
            fill.color = t.recording
            c.drawCircle(mx, cy, 17 * dp, fill)
            fill.color = 0xFFFFFFFF.toInt()
            if (dictation == Dictation.LISTENING) {
                rect.set(mx - 6 * dp, cy - 6 * dp, mx + 6 * dp, cy + 6 * dp); c.drawRoundRect(rect, 2 * dp, 2 * dp, fill)
            } else {
                for (i in -1..1) c.drawCircle(mx + i * 6 * dp, cy, 2 * dp, fill)
            }
        } else {
            drawMicIcon(c, mx, cy, 1f, t.text)
        }
        // divider after mic
        fill.color = t.divider
        c.drawRect(micW, cy - 12 * dp, micW + 1 * dp, cy + 12 * dp, fill)

        val left = micW + 4 * dp
        val w = width - left
        if (dictation != Dictation.OFF) {
            text.color = t.hint; text.typeface = Typeface.DEFAULT; text.textSize = 15 * sp
            val label = dictationText.ifEmpty { if (dictation == Dictation.LISTENING) "ئاڭلاۋاتىدۇ…" else "…" }
            val shown = TextUtils.ellipsize(label, android.text.TextPaint(text), w - 16 * dp, TextUtils.TruncateAt.START).toString()
            c.drawText(shown, left + w / 2, cy - (text.descent() + text.ascent()) / 2, text)
            return
        }
        val slotW = w / 3
        for (i in 0..2) {
            val s = slots.getOrNull(i).orEmpty()
            val x0 = left + i * slotW
            if (i > 0 && (s.isNotEmpty() || slots.getOrNull(i - 1).orEmpty().isNotEmpty())) {
                fill.color = t.divider; c.drawRect(x0, cy - 10 * dp, x0 + 1 * dp, cy + 10 * dp, fill)
            }
            if (s.isEmpty()) continue
            val pressed = pointers.values.any { it.stripTarget == i }
            if (pressed) {
                fill.color = t.keyPressed
                rect.set(x0 + 3 * dp, 5 * dp, x0 + slotW - 3 * dp, stripH - 5 * dp); c.drawRoundRect(rect, radius, radius, fill)
            }
            text.color = t.text
            text.typeface = if (i == 1) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            text.textSize = 17 * sp
            val shown = fitText(s, slotW - 10 * dp)
            c.drawText(shown, x0 + slotW / 2, cy - (text.descent() + text.ascent()) / 2, text)
        }
    }

    private fun fitText(s: String, maxW: Float): String {
        var size = text.textSize
        while (text.measureText(s) > maxW && size > 12 * sp) { size -= 1 * sp; text.textSize = size }
        return TextUtils.ellipsize(s, android.text.TextPaint(text), maxW, TextUtils.TruncateAt.END).toString()
    }

    private fun drawKey(c: Canvas, g: KeyboardGeometry, b: KeyBox, pressed: Boolean) {
        val t = theme
        val k = b.key
        val v = g.visualBounds(b)
        rect.set(v[0] + gapX, v[1] + gapY, v[2] - gapX, v[3] - gapY)
        val isFunction = k.type != KeyType.CHAR && k.type != KeyType.SPACE
        val lockedShift = k.type == KeyType.SHIFT && shiftState == ShiftState.LOCKED
        fill.color = when {
            pressed -> t.keyPressed
            lockedShift -> t.accent
            isFunction -> t.functionKey
            else -> t.key
        }
        // subtle bottom shadow like physical keys
        shadowPaint.color = (((t.shadow ushr 24) / 2) shl 24) or (t.shadow and 0xFFFFFF)
        c.drawRoundRect(rect.left, rect.top + 1.2f * dp, rect.right, rect.bottom + 1.2f * dp, radius, radius, shadowPaint)
        c.drawRoundRect(rect, radius, radius, fill)

        val cx = rect.centerX(); val cy = rect.centerY()
        val ink = if (lockedShift) t.accentText else t.text
        when (k.type) {
            KeyType.SHIFT -> drawShiftIcon(c, cx, cy, if (shiftState == ShiftState.ON) t.accent else ink, shiftState != ShiftState.OFF)
            KeyType.BACKSPACE -> drawBackspaceIcon(c, cx, cy, ink)
            KeyType.LANGUAGE -> drawGlobeIcon(c, cx, cy, ink)
            KeyType.ENTER -> {
                val label = enterLabel
                if (label == null) drawEnterIcon(c, cx, cy, ink) else drawLabel(c, label, cx, cy, 15 * sp, ink, rect.width() - 6 * dp)
            }
            KeyType.SPACE -> drawLabel(c, k.label, cx, cy, 13 * sp, t.hint, rect.width() - 8 * dp)
            KeyType.CHAR -> {
                val size = if (k.label.length > 1) 17 * sp else 23 * sp
                drawLabel(c, k.label, cx, cy, size, ink, rect.width())
                k.hint?.let { h ->
                    text.textSize = 10.5f * sp; text.color = t.hint; text.typeface = Typeface.DEFAULT
                    c.drawText(h, rect.right - 7 * dp, rect.top + 13 * dp, text)
                }
            }
            else -> drawLabel(c, k.label, cx, cy, 15 * sp, ink, rect.width() - 4 * dp)
        }
    }

    private fun drawLabel(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int, maxW: Float) {
        text.typeface = Typeface.DEFAULT; text.textSize = size; text.color = color
        while (text.measureText(s) > maxW && text.textSize > 9 * sp) text.textSize -= 1 * sp
        c.drawText(s, cx, cy - (text.descent() + text.ascent()) / 2, text)
    }

    private fun drawPreview(c: Canvas, g: KeyboardGeometry, b: KeyBox) {
        val t = theme
        val v = g.visualBounds(b)
        val kw = v[2] - v[0] - 2 * gapX
        val bw = max(kw * 1.25f, 46 * dp)
        val bh = rowH * 1.08f
        val keyTop = v[1] + gapY
        var top = keyTop - bh + rowH * 0.12f
        if (top < 2 * dp) top = 2 * dp
        var left = (v[0] + v[2]) / 2 - bw / 2
        left = left.coerceIn(2 * dp, width - bw - 2 * dp)
        rect.set(left, top, left + bw, top + bh)
        shadowPaint.color = t.shadow
        c.drawRoundRect(rect.left, rect.top + 2 * dp, rect.right, rect.bottom + 3 * dp, radius * 1.3f, radius * 1.3f, shadowPaint)
        fill.color = t.bubble
        c.drawRoundRect(rect, radius * 1.3f, radius * 1.3f, fill)
        drawLabel(c, b.key.label, rect.centerX(), rect.centerY(), 30 * sp, t.text, bw)
    }

    private fun drawPopup(c: Canvas, p: Pointer) {
        val t = theme
        val r = p.popupRect
        shadowPaint.color = t.shadow
        c.drawRoundRect(r.left, r.top + 2 * dp, r.right, r.bottom + 3 * dp, radius * 1.3f, radius * 1.3f, shadowPaint)
        fill.color = t.bubble
        c.drawRoundRect(r, radius * 1.3f, radius * 1.3f, fill)
        val cw = r.width() / p.popup.size
        p.popup.forEachIndexed { i, s ->
            val x0 = r.left + i * cw
            if (i == p.popupSel) {
                fill.color = t.accent
                rect.set(x0 + 3 * dp, r.top + 3 * dp, x0 + cw - 3 * dp, r.bottom - 3 * dp)
                c.drawRoundRect(rect, radius, radius, fill)
            }
            drawLabel(c, s, x0 + cw / 2, r.centerY(), 22 * sp, if (i == p.popupSel) t.accentText else t.text, cw)
        }
    }

    // ─── vector icons (monochrome, like system keyboards) ───

    private fun iconStroke(color: Int, w: Float = 1.9f) {
        stroke.color = color; stroke.strokeWidth = w * dp; stroke.style = Paint.Style.STROKE
    }

    private fun drawShiftIcon(c: Canvas, cx: Float, cy: Float, color: Int, filled: Boolean) {
        val s = 9 * dp
        path.reset()
        path.moveTo(cx, cy - s)
        path.lineTo(cx + s, cy + 0.1f * s); path.lineTo(cx + 0.45f * s, cy + 0.1f * s)
        path.lineTo(cx + 0.45f * s, cy + s); path.lineTo(cx - 0.45f * s, cy + s)
        path.lineTo(cx - 0.45f * s, cy + 0.1f * s); path.lineTo(cx - s, cy + 0.1f * s); path.close()
        if (filled) { fill.color = color; c.drawPath(path, fill) } else { iconStroke(color); c.drawPath(path, stroke) }
    }

    private fun drawBackspaceIcon(c: Canvas, cx: Float, cy: Float, color: Int) {
        val w = 11 * dp; val h = 7.5f * dp
        path.reset()
        path.moveTo(cx - w, cy); path.lineTo(cx - w * 0.45f, cy - h); path.lineTo(cx + w, cy - h)
        path.lineTo(cx + w, cy + h); path.lineTo(cx - w * 0.45f, cy + h); path.close()
        iconStroke(color); c.drawPath(path, stroke)
        val x = cx + w * 0.28f; val d = 3.2f * dp
        c.drawLine(x - d, cy - d, x + d, cy + d, stroke); c.drawLine(x - d, cy + d, x + d, cy - d, stroke)
    }

    private fun drawEnterIcon(c: Canvas, cx: Float, cy: Float, color: Int) {
        val w = 9 * dp; val h = 6 * dp
        iconStroke(color)
        path.reset()
        path.moveTo(cx + w, cy - h); path.lineTo(cx + w, cy + 2 * dp); path.lineTo(cx - w, cy + 2 * dp)
        c.drawPath(path, stroke)
        path.reset()
        path.moveTo(cx - w + 4 * dp, cy - 2 * dp); path.lineTo(cx - w, cy + 2 * dp); path.lineTo(cx - w + 4 * dp, cy + 6 * dp)
        c.drawPath(path, stroke)
    }

    private fun drawGlobeIcon(c: Canvas, cx: Float, cy: Float, color: Int) {
        val r = 9 * dp
        iconStroke(color, 1.6f)
        c.drawCircle(cx, cy, r, stroke)
        rect.set(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r); c.drawOval(rect, stroke)
        c.drawLine(cx - r, cy, cx + r, cy, stroke)
        c.drawLine(cx - r * 0.85f, cy - r * 0.5f, cx + r * 0.85f, cy - r * 0.5f, stroke)
        c.drawLine(cx - r * 0.85f, cy + r * 0.5f, cx + r * 0.85f, cy + r * 0.5f, stroke)
    }

    private fun drawMicIcon(c: Canvas, cx: Float, cy: Float, scale: Float, color: Int) {
        val s = scale * dp
        fill.color = color
        rect.set(cx - 4.5f * s, cy - 11 * s, cx + 4.5f * s, cy + 3 * s); c.drawRoundRect(rect, 4.5f * s, 4.5f * s, fill)
        iconStroke(color, 1.8f)
        rect.set(cx - 8 * s, cy - 6 * s, cx + 8 * s, cy + 7 * s); c.drawArc(rect, 0f, 180f, false, stroke)
        c.drawLine(cx, cy + 7 * s, cx, cy + 11 * s, stroke)
        c.drawLine(cx - 4 * s, cy + 11 * s, cx + 4 * s, cy + 11 * s, stroke)
    }

    // ═══════════════════════════════ touch ═══════════════════════════════

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                up(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    private fun feedback(k: Key?) {
        if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (sound) {
            val fx = when (k?.type) {
                KeyType.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
                KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio?.playSoundEffect(fx, -1f)
        }
    }

    private fun down(id: Int, x: Float, y: Float) {
        // a new finger commits keys still held by other fingers (fast two-thumb typing)
        for (o in pointers.values) {
            val k = o.box?.key ?: continue
            if (k.type == KeyType.CHAR && !o.consumed && o.popup.isEmpty()) {
                o.consumed = true; o.longPress?.let(handler::removeCallbacks)
                listener?.onText(k.output)
            }
        }
        if (y < stripH) {
            val p = Pointer(id, null, x, y)
            p.stripTarget = stripTargetAt(x)
            pointers[id] = p; invalidate(); return
        }
        val box = geo().keyAt(x, y) ?: return
        val p = Pointer(id, box, x, y)
        pointers[id] = p
        feedback(box.key)
        when (box.key.type) {
            KeyType.CHAR -> scheduleLongPress(p)
            KeyType.BACKSPACE -> {
                listener?.onBackspace(false)
                var n = 0
                val r = object : Runnable {
                    override fun run() {
                        n++
                        listener?.onBackspace(n > 20)        // after ~1.5 s, delete whole words
                        if (haptics && n % 3 == 0) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        handler.postDelayed(this, if (n > 20) 180L else 55L)
                    }
                }
                p.repeat = r; handler.postDelayed(r, 420)
            }
            KeyType.LANGUAGE -> {
                val r = Runnable { p.longFired = true; listener?.onLanguageLongPress() }
                p.longPress = r; handler.postDelayed(r, 450)
            }
            else -> {}
        }
        invalidate()
    }

    private fun scheduleLongPress(p: Pointer) {
        p.longPress?.let(handler::removeCallbacks)
        val k = p.box?.key ?: return
        if (k.popup.isEmpty()) return
        val r = Runnable { openPopup(p) }
        p.longPress = r; handler.postDelayed(r, 320)
    }

    private fun openPopup(p: Pointer) {
        val b = p.box ?: return
        val items = b.key.popup
        if (items.isEmpty() || p.consumed) return
        val g = geo()
        val v = g.visualBounds(b)
        val cw = max((v[2] - v[0]) * 0.95f, 40 * dp)
        val w = min(cw * items.size, width - 4 * dp)
        val h = rowH * 0.95f
        val bottom = v[1] + gapY + 4 * dp
        val top = max(2 * dp, bottom - h)
        var left = (v[0] + v[2]) / 2 - w / 2
        left = left.coerceIn(2 * dp, width - w - 2 * dp)
        p.popup = items
        p.popupRect = RectF(left, top, left + w, top + h)
        p.popupSel = 0
        if (haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun move(id: Int, x: Float, y: Float) {
        val p = pointers[id] ?: return
        if (p.stripTarget != -2) return
        val b = p.box ?: return
        if (p.popup.isNotEmpty()) {
            val r = p.popupRect
            val sel = ((x - r.left) / (r.width() / p.popup.size)).toInt().coerceIn(0, p.popup.lastIndex)
            if (sel != p.popupSel) { p.popupSel = sel; invalidate() }
            return
        }
        when (b.key.type) {
            KeyType.SPACE -> {
                if (!p.cursorMode && abs(x - p.downX) > 18 * dp) { p.cursorMode = true; p.lastCursorX = x; invalidate() }
                if (p.cursorMode) {
                    val step = 11 * dp
                    while (x - p.lastCursorX >= step) { listener?.onCursor(+1); p.lastCursorX += step }
                    while (p.lastCursorX - x >= step) { listener?.onCursor(-1); p.lastCursorX -= step }
                }
            }
            KeyType.CHAR -> {
                if (p.consumed) return
                val nb = geo().keyAt(x, y) ?: return
                if (nb !== b && nb.key.type == KeyType.CHAR) {      // finger slid to another letter
                    p.box = nb; scheduleLongPress(p); invalidate()
                }
            }
            else -> {}
        }
    }

    private fun up(id: Int, x: Float, y: Float) {
        val p = pointers.remove(id) ?: return
        p.longPress?.let(handler::removeCallbacks)
        p.repeat?.let(handler::removeCallbacks)
        if (p.stripTarget != -2) {
            val target = stripTargetAt(x)
            if (target == p.stripTarget && y < stripH * 1.4f) {
                if (target == -1) { feedback(null); listener?.onMicTap() }
                else slots.getOrNull(target)?.takeIf { it.isNotEmpty() && dictation == Dictation.OFF }?.let {
                    feedback(null); listener?.onSuggestion(it)
                }
            }
            invalidate(); return
        }
        val k = p.box?.key
        if (k != null) when {
            p.popup.isNotEmpty() -> listener?.onText(p.popup[p.popupSel])
            k.type == KeyType.CHAR -> if (!p.consumed) listener?.onText(k.output)
            k.type == KeyType.SPACE -> if (!p.cursorMode) listener?.onText(" ")
            k.type == KeyType.BACKSPACE -> {}
            k.type == KeyType.LANGUAGE -> if (!p.longFired) listener?.onFunctionKey(k)
            else -> listener?.onFunctionKey(k)
        }
        invalidate()
    }

    private fun stripTargetAt(x: Float): Int {
        if (x < micW) return -1
        if (dictation != Dictation.OFF) return -3
        val slotW = (width - micW - 4 * dp) / 3
        return ((x - micW - 4 * dp) / slotW).toInt().coerceIn(0, 2)
    }

    private fun cancelAll() {
        for (p in pointers.values) { p.longPress?.let(handler::removeCallbacks); p.repeat?.let(handler::removeCallbacks) }
        pointers.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelAll()
        super.onDetachedFromWindow()
    }

    // ─── for screenshot tests: show a pressed key / long-press popup without touching ───
    fun debugPress(output: String, popup: Boolean) {
        if (width == 0) { pendingDebug = output to popup; return }
        applyDebugPress(output, popup)
    }

    private fun applyDebugPress(output: String, popup: Boolean) {
        val b = geo().boxes.firstOrNull { it.key.output == output } ?: return
        val p = Pointer(99, b, b.centerX, b.centerY)
        pointers[99] = p
        if (popup) openPopup(p)
        invalidate()
    }
}

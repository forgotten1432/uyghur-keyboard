package com.uyghur.keyboard.keyboard

/** A key placed on screen (pixels). The touch area covers the gaps between keys. */
data class KeyBox(
    val key: Key,
    val row: Int,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val width get() = right - left
    val height get() = bottom - top
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

/**
 * Places a [Layout] in a rectangle and finds the key under a finger.
 * Every point of the keyboard area belongs to the nearest key — no dead zones between keys.
 */
class KeyboardGeometry(
    val layout: Layout,
    val width: Float,
    val top: Float,
    val rowHeight: Float,
) {
    val boxes: List<KeyBox>
    val bottom: Float = top + rowHeight * layout.rows.size

    init {
        val list = mutableListOf<KeyBox>()
        layout.rows.forEachIndexed { r, row ->
            val units = row.keys.sumOf { it.width.toDouble() }.toFloat() + 2 * row.indent
            val unit = width / maxOf(10f, units)
            var x = (width - unit * (units - 2 * row.indent)) / 2
            val y = top + r * rowHeight
            for ((i, k) in row.keys.withIndex()) {
                var l = x
                var rgt = x + k.width * unit
                // first/last key of an indented row also take the indent (touch area to the edge)
                if (i == 0) l = 0f
                if (i == row.keys.lastIndex) rgt = width
                list += KeyBox(k, r, l, y, rgt, y + rowHeight)
                x += k.width * unit
            }
        }
        boxes = list
    }

    /** Visual bounds (without the extended edge touch area) of a key. */
    fun visualBounds(box: KeyBox): FloatArray {
        val row = layout.rows[box.row]
        val units = row.keys.sumOf { it.width.toDouble() }.toFloat() + 2 * row.indent
        val unit = width / maxOf(10f, units)
        val start = (width - unit * (units - 2 * row.indent)) / 2
        var x = start
        for (k in row.keys) {
            if (k === box.key) return floatArrayOf(x, box.top, x + k.width * unit, box.bottom)
            x += k.width * unit
        }
        return floatArrayOf(box.left, box.top, box.right, box.bottom)
    }

    /** Key under (x, y); points above/below the keys snap to the first/last row. Null outside. */
    fun keyAt(x: Float, y: Float): KeyBox? {
        if (y < top - rowHeight * 0.25f || boxes.isEmpty()) return null
        val r = ((y - top) / rowHeight).toInt().coerceIn(0, layout.rows.lastIndex)
        val inRow = boxes.filter { it.row == r }
        return inRow.firstOrNull { x >= it.left && x < it.right }
            ?: inRow.minByOrNull { minOf(Math.abs(x - it.left), Math.abs(x - it.right)) }
    }
}

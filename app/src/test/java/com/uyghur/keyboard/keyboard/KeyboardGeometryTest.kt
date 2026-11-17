package com.uyghur.keyboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardGeometryTest {
    private val g = KeyboardGeometry(Layouts.english(ShiftState.OFF, false), width = 1000f, top = 100f, rowHeight = 50f)

    @Test fun findsKeyAtItsCentre() {
        for (b in g.boxes) assertEquals(b.key, g.keyAt(b.centerX, b.centerY)!!.key)
    }

    @Test fun noDeadZones() {
        // every point of the keyboard area maps to some key
        var y = 100f
        while (y < g.bottom) { var x = 0f; while (x < 1000f) { assertNotNull(g.keyAt(x, y)); x += 7f }; y += 5f }
    }

    @Test fun indentedRowEdgesBelongToFirstAndLastKey() {
        assertEquals("a", g.keyAt(5f, 175f)!!.key.output)      // left of "a" (half-key indent)
        assertEquals("l", g.keyAt(995f, 175f)!!.key.output)
    }

    @Test fun aboveKeyboardIsNothing() {
        assertNull(g.keyAt(500f, 10f))
    }

    @Test fun numberRowIsLowerAndHitTestFollowsIt() {
        val n = KeyboardGeometry(Layouts.english(ShiftState.OFF, true), width = 1000f, top = 0f, rowHeight = 50f)
        val one = n.boxes.first { it.key.output == "1" }
        assertEquals(40f, one.height, 0.01f)
        assertEquals("q", n.keyAt(50f, 41f)!!.key.output)      // just below the number row
        assertEquals("1", n.keyAt(50f, 39f)!!.key.output)
        assertEquals(240f, n.bottom, 0.01f)
    }

    @Test fun spaceIsWide() {
        val space = g.boxes.first { it.key.type == KeyType.SPACE }
        assertEquals(400f, space.width, 0.5f)
    }
}

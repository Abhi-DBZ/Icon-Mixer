package com.iconforge.app

import com.iconforge.app.export.ShapePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapePathsTest {
    private fun points(svg: String) = Regex("""(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""").findAll(svg)
        .map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList()

    @Test fun newShapesFillTheBox() {
        for (name in listOf("HEXAGON", "FLOWER", "PEBBLE")) {
            val pts = points(assertNotNull(ShapePaths.svg(name)).let { ShapePaths.svg(name)!! })
            assertTrue(name, pts.size > 20)
            assertTrue(name, pts.all { (x, y) -> x in -0.01..100.01 && y in -0.01..100.01 })
            // Touches the box on its longer side.
            val w = pts.maxOf { it.first } - pts.minOf { it.first }
            val h = pts.maxOf { it.second } - pts.minOf { it.second }
            assertEquals(name, 100.0, maxOf(w, h), 0.05)
        }
        assertNotNull(ShapePaths.svg("LEAF"))
        assertNull(ShapePaths.svg("DEFAULT"))
    }

    @Test fun insetFollowsTheInnerSize() {
        // Normal size = -25 %, the value aapt2 compiles "-25%" to.
        assertEquals(0xe0000030.toInt(), ShapePaths.insetFraction(1f))
        fun value(c: Int) = (c shr 8) / (1 shl 23).toDouble()
        assertEquals(-0.40, value(ShapePaths.insetFraction(1.2f)), 1e-6)   // 1.5*1.2 = 1.8 → -40 %
        assertEquals(-0.10, value(ShapePaths.insetFraction(0.8f)), 1e-6)   // 1.5*0.8 = 1.2 → -10 %
    }
}

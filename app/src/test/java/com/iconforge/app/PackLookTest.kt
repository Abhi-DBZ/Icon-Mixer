package com.iconforge.app

import com.iconforge.app.core.IconShape
import com.iconforge.app.core.PackLookAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackLookTest {
    private val s = 64

    /** A plate (circle or square) with a glyph block in the middle. */
    private fun icon(plate: Int, glyph: Int, circle: Boolean, glyphHalf: Int, seed: Int = 0): IntArray {
        val p = IntArray(s * s)
        val c = s / 2.0
        for (y in 0 until s) for (x in 0 until s) {
            val inPlate = if (circle) (x - c + 0.5) * (x - c + 0.5) + (y - c + 0.5) * (y - c + 0.5) <= 31.0 * 31.0
                else x in 1 until s - 1 && y in 1 until s - 1
            if (!inPlate) continue
            // A different glyph for each "app": a block offset a little by seed.
            val gx = x - 32 + seed % 3; val gy = y - 32
            p[y * s + x] = if (gx in -glyphHalf until glyphHalf && gy in -glyphHalf until glyphHalf && (gx + gy + seed) % 5 != 0) glyph else plate
        }
        return p
    }

    @Test
    fun nothingStylePack() {
        val dark = 0xFF1C1C1E.toInt(); val white = 0xFFF5F5F5.toInt()
        val look = PackLookAnalyzer.analyze((0 until 20).map { icon(dark, white, circle = true, glyphHalf = 14, seed = it) }, s)
        assertNotNull(look); look!!
        assertEquals(IconShape.CIRCLE, look.shape)
        assertTrue(PackLookAnalyzer.dist(look.plate, dark) < 10)
        assertTrue(PackLookAnalyzer.dist(look.glyph, white) < 10)
        assertTrue("glyph size ${look.glyphSize}", look.glyphSize in 0.4f..0.5f)
    }

    @Test
    fun squarePlates() {
        val look = PackLookAnalyzer.analyze((0 until 12).map { icon(0xFF202020.toInt(), 0xFFFFFFFF.toInt(), circle = false, glyphHalf = 12, seed = it) }, s)
        assertEquals(IconShape.SQUARE, look?.shape)
    }

    @Test
    fun logoCutFromColourIcon() {
        // Yellow square icon with a dark block "logo" in the middle (like a coloured app icon).
        val px = icon(0xFFFFD54F.toInt(), 0xFF212121.toInt(), circle = false, glyphHalf = 12)
        val mask = PackLookAnalyzer.logoMask(px, s)
        assertNotNull(mask)
        assertEquals(0xFF.toByte(), mask!![32 * s + 31])   // logo pixel
        assertEquals(0.toByte(), mask[6 * s + 32])          // background pixel
        // A plain icon with no logo gives nothing.
        assertNull(PackLookAnalyzer.logoMask(icon(0xFF1E88E5.toInt(), 0xFF1E88E5.toInt(), circle = false, glyphHalf = 12), s))
    }

    @Test
    fun colourfulPackIsNotTwoTone() {
        val colours = listOf(0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFFB300, 0xFF8E24AA, 0xFF00897B, 0xFFF4511E, 0xFF6D4C41)
            .map { it.toInt() }
        val icons = colours.mapIndexed { i, c -> icon(c, colours[(i + 3) % colours.size], circle = true, glyphHalf = 14, seed = i) }
        assertNull(PackLookAnalyzer.analyze(icons, s))
    }
}

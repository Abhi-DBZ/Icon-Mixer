package com.iconforge.app.core

import kotlin.math.max
import kotlin.math.sqrt

/**
 * The look of a two-tone icon pack (e.g. Nothing-style: a white glyph on a dark circle),
 * learned from its icons so generated icons can copy it.
 *
 * @param plate  ARGB colour of the plate behind the glyph
 * @param glyph  ARGB colour of the glyph
 * @param shape  plate shape the pack uses (CIRCLE, SQUIRCLE or SQUARE)
 * @param plateSize plate width as a fraction of the icon (packs often leave padding)
 * @param glyphSize glyph bounding box as a fraction of the icon
 */
data class PackLook(val plate: Int, val glyph: Int, val shape: IconShape, val plateSize: Float, val glyphSize: Float)

/** Pure pixel maths (no Android types), unit-tested on the PC. */
object PackLookAnalyzer {
    private fun r(c: Int) = (c ushr 16) and 0xFF
    private fun g(c: Int) = (c ushr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun a(c: Int) = (c ushr 24) and 0xFF

    fun dist(c1: Int, c2: Int): Double {
        val dr = (r(c1) - r(c2)).toDouble(); val dg = (g(c1) - g(c2)).toDouble(); val db = (b(c1) - b(c2)).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun median(cs: List<Int>): Int {
        fun m(f: (Int) -> Int) = cs.map(f).sorted()[cs.size / 2]
        return (0xFF shl 24) or (m(::r) shl 16) or (m(::g) shl 8) or m(::b)
    }

    private fun share(cs: List<Int>, centre: Int, within: Double) = cs.count { dist(it, centre) <= within }.toDouble() / cs.size

    /**
     * The logo inside a normal (full-colour) app icon: opaque pixels that stand out from the
     * icon's own background colour. Returns an alpha mask, or null when nothing clear stands out
     * (or the "logo" would be most of the icon). [px] is a square ARGB array of side [s].
     */
    fun logoMask(px: IntArray, s: Int): ByteArray? {
        val m = (s * 0.14f).toInt(); val c = s / 2
        val edge = listOf(px[m * s + c], px[c * s + m], px[c * s + s - 1 - m], px[(s - 1 - m) * s + c]).filter { a(it) > 200 }
        if (edge.size < 3) return null
        val bg = median(edge)
        val mask = ByteArray(s * s)
        var n = 0
        for (i in px.indices) {
            val q = px[i]
            if (a(q) > 200 && dist(q, bg) > 70) { mask[i] = 0xFF.toByte(); n++ }
        }
        val share = n.toDouble() / (s * s)
        return if (share in 0.03..0.5) mask else null
    }

    /**
     * [icons]: square ARGB pixel arrays of side [s] (pack icons for real apps).
     * Returns null when the pack isn't a consistent two-tone pack (e.g. full-colour icons).
     */
    fun analyze(icons: List<IntArray>, s: Int): PackLook? {
        val plates = ArrayList<Int>(); val glyphs = ArrayList<Int>()
        val glyphExt = ArrayList<Float>(); val plateExt = ArrayList<Float>()
        var corner = 0; var diagonal = 0; var counted = 0
        fun px(p: IntArray, x: Int, y: Int) = p[y * s + x]

        for (p in icons) {
            if (p.size != s * s) continue
            // Plate colour: four points just inside the edges, all opaque and alike.
            val m = (s * 0.14f).toInt(); val c = s / 2
            val edge = listOf(px(p, c, m), px(p, m, c), px(p, s - 1 - m, c), px(p, c, s - 1 - m))
            if (edge.any { a(it) < 200 }) continue
            val plate = median(edge)
            if (edge.any { dist(it, plate) > 40 }) continue
            counted++
            plates += plate
            if (a(px(p, 1, 1)) < 60) corner++                        // transparent corner → not square
            val d = (s * 0.14f).toInt()
            if (a(px(p, d, d)) < 60) diagonal++                      // transparent at 14% in → circle

            // Plate extent along the middle row.
            var l = 0; while (l < c && a(px(p, l, c)) < 128) l++
            plateExt += (s - 2 * l).toFloat() / s

            // Glyph: opaque pixels clearly unlike the plate.
            var gr = 0L; var gg = 0L; var gb = 0L; var n = 0
            var x0 = s; var y0 = s; var x1 = -1; var y1 = -1
            for (y in 0 until s) for (x in 0 until s) {
                val q = px(p, x, y)
                if (a(q) > 200 && dist(q, plate) > 90) {
                    gr += r(q); gg += g(q); gb += b(q); n++
                    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                }
            }
            if (n < s * s / 200) continue
            glyphs += (0xFF shl 24) or ((gr / n).toInt() shl 16) or ((gg / n).toInt() shl 8) or (gb / n).toInt()
            glyphExt += max(x1 - x0 + 1, y1 - y0 + 1).toFloat() / s
        }
        if (plates.size < 6 || glyphs.size < 6) return null
        val plate = median(plates); val glyph = median(glyphs)
        // Two-tone packs repeat the same plate and glyph colours; colourful packs don't.
        if (share(plates, plate, 60.0) < 0.7 || share(glyphs, glyph, 80.0) < 0.6) return null
        if (dist(plate, glyph) < 90) return null
        val shape = when {
            corner.toDouble() / counted < 0.5 -> IconShape.SQUARE
            diagonal.toDouble() / counted >= 0.6 -> IconShape.CIRCLE
            else -> IconShape.SQUIRCLE
        }
        return PackLook(plate, glyph, shape, plateExt.sorted()[plateExt.size / 2].coerceIn(0.6f, 1f),
            glyphExt.sorted()[glyphExt.size / 2].coerceIn(0.25f, 0.8f))
    }
}

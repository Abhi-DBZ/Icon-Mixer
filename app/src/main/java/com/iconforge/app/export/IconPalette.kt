package com.iconforge.app.export

import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeTonalSpot

/**
 * Android's wallpaper palette (@android:color/system_accent1_100 …) recomputed from a chosen
 * colour, the way Android 12+ builds it from the wallpaper. Live Material You icons that refer
 * to those colours get the chosen palette instead.
 */
object IconPalette {
    private val toneName = Regex("""^system_(accent1|accent2|accent3|neutral1|neutral2)_(\d+)$""")

    /** The phone's current wallpaper palette: framework colour id → ARGB. */
    fun current(ctx: android.content.Context): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for (f in android.R.color::class.java.fields) {
            if (toneName.matches(f.name)) runCatching { val id = f.getInt(null); out[id] = ctx.getColor(id) }
        }
        return out
    }

    /**
     * Preview only: [b] (drawn with the wallpaper palette [from]) with each wallpaper colour
     * swapped for the same shade of the chosen palette [to]. Pixels that aren't close to a
     * wallpaper colour (pictures, white glyphs) stay as they are.
     */
    fun recolour(b: android.graphics.Bitmap, from: Map<Int, Int>, to: Map<Int, Int>): android.graphics.Bitmap {
        val pairs = from.mapNotNull { (id, c) -> to[id]?.let { c to it } }.distinctBy { it.first }
        if (pairs.isEmpty()) return b
        val w = b.width; val h = b.height
        val px = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        val memo = HashMap<Int, Int>()
        for (i in px.indices) {
            val p = px[i]; val a = p ushr 24
            if (a == 0) continue
            val rgb = p and 0xFFFFFF
            val mapped = memo.getOrPut(rgb) {
                var best = -1; var bestD = Int.MAX_VALUE
                for ((src, dst) in pairs) {
                    val dr = ((src shr 16) and 0xFF) - ((rgb shr 16) and 0xFF)
                    val dg = ((src shr 8) and 0xFF) - ((rgb shr 8) and 0xFF)
                    val db = (src and 0xFF) - (rgb and 0xFF)
                    val d = dr * dr + dg * dg + db * db
                    if (d < bestD) { bestD = d; best = dst }
                }
                if (bestD <= 24 * 24 * 3) best and 0xFFFFFF else rgb
            }
            px[i] = (a shl 24) or mapped
        }
        return android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
    }

    /** Framework colour id → ARGB for [seed]. Grey seeds give a black-and-white palette. */
    fun forSeed(seed: Int): Map<Int, Int> {
        val hct = Hct.fromInt(seed)
        val scheme: DynamicScheme = if (hct.chroma < 6.0) SchemeMonochrome(hct, false, 0.0) else SchemeTonalSpot(hct, false, 0.0)
        val out = HashMap<Int, Int>()
        for (f in android.R.color::class.java.fields) {
            val m = toneName.find(f.name) ?: continue
            val palette: TonalPalette = when (m.groupValues[1]) {
                "accent1" -> scheme.primaryPalette
                "accent2" -> scheme.secondaryPalette
                "accent3" -> scheme.tertiaryPalette
                "neutral1" -> scheme.neutralPalette
                else -> scheme.neutralVariantPalette
            }
            // system_*_0 = tone 100 (white) … system_*_1000 = tone 0 (black).
            val tone = 100 - m.groupValues[2].toInt() / 10
            runCatching { out[f.getInt(null)] = palette.tone(tone) }
        }
        return out
    }
}

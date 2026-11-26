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

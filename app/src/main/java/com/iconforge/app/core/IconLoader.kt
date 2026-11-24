package com.iconforge.app.core

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import com.iconforge.app.data.InstalledApp
import com.iconforge.app.data.MappingEntity
import com.iconforge.app.data.Source
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where an icon comes from. [key] identifies it in the bitmap cache. */
sealed interface IconSrc {
    val key: String

    data class App(val packageName: String, val activityName: String) : IconSrc {
        override val key get() = "app:$packageName/$activityName"
    }

    data class Pack(val pack: String, val drawable: String) : IconSrc {
        override val key get() = "pack:$pack:$drawable"
    }

    data class FileImage(val path: String) : IconSrc {
        override val key get() = "file:$path"
    }

    /** The app's own icon redrawn with a pack's style kit (back plate, mask, overlay, scale). */
    data class Generated(val pack: String, val packageName: String, val activityName: String) : IconSrc {
        override val key get() = "gen:$pack:$packageName/$activityName"
    }

    /** [base] resized and/or placed on a coloured plate. */
    data class Adjusted(val base: IconSrc, val scale: Float, val bgColor: Int?) : IconSrc {
        override val key get() = "adj:$scale:$bgColor:${base.key}"
    }

    companion object {
        fun original(app: InstalledApp) = App(app.packageName, app.activityName)

        /** Icon a mapping assigns (with its adjustments); null when the app has no assignment. */
        fun of(m: MappingEntity?, app: InstalledApp?): IconSrc? {
            if (m == null) return null
            val base = when (m.source) {
                Source.PACK -> if (m.iconPack != null && m.iconResource != null) Pack(m.iconPack, m.iconResource) else null
                Source.CUSTOM -> m.customPath?.let { FileImage(it) }
                Source.ORIGINAL -> app?.let { original(it) } ?: App(m.packageName, m.componentKey.substringAfter('/'))
                Source.GENERATED -> m.iconPack?.let { Generated(it, m.packageName, m.componentKey.substringAfter('/')) }
                else -> null
            } ?: return null
            return if (m.scale != 1f || m.bgColor != null) Adjusted(base, m.scale, m.bgColor) else base
        }
    }
}

/** Loads icons as bitmaps, with a memory-bounded cache shared by the whole UI. */
class IconLoader(private val context: Context, private val packs: IconPacks, private val settings: com.iconforge.app.data.Settings) {
    private val pm = context.packageManager
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt().coerceAtLeast(8 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount / 1024
    }

    private fun cacheKey(src: IconSrc, sizePx: Int, shape: IconShape) =
        "${src.key}@$sizePx#${shape.name}${if (settings.shapeAllIcons.value) "+all" else ""}"

    fun peek(src: IconSrc, sizePx: Int, shape: IconShape = IconShape.DEFAULT): Bitmap? = cache.get(cacheKey(src, sizePx, shape))

    suspend fun bitmap(src: IconSrc, sizePx: Int, shape: IconShape = IconShape.DEFAULT): Bitmap? {
        val ck = cacheKey(src, sizePx, shape)
        cache.get(ck)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { render(src, sizePx, shape) }.getOrNull()?.also { cache.put(ck, it) }
        }
    }

    /** Light/dark mode forced for the render running on this thread (null = phone's current mode). */
    private val forcedNight = ThreadLocal<Boolean?>()

    /** Full-quality bitmap for export (not cached). [night]: draw pack icons in light/dark mode. */
    fun renderForExport(src: IconSrc, sizePx: Int, shape: IconShape = IconShape.DEFAULT, night: Boolean? = null): Bitmap? {
        val prev = forcedNight.get()
        forcedNight.set(night)
        return try { runCatching { render(src, sizePx, shape) }.getOrNull() } finally { forcedNight.set(prev) }
    }

    private fun render(src: IconSrc, sizePx: Int, shape: IconShape): Bitmap? = when (src) {
        is IconSrc.FileImage -> BitmapFactory.decodeFile(src.path)?.let {
            if (it.width == sizePx && it.height == sizePx) it else Bitmap.createScaledBitmap(it, sizePx, sizePx, true)
        }
        is IconSrc.Generated -> renderGenerated(src, sizePx, shape)
        is IconSrc.Adjusted -> renderAdjusted(src, sizePx, shape)
        else -> drawable(src)?.let { d ->
            if (shape != IconShape.DEFAULT && d is AdaptiveIconDrawable) renderAdaptive(d, sizePx, shape)
            else d.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).let {
                // "Apply the shape to every icon": cut ordinary (non-adaptive) icons too.
                if (settings.shapeAllIcons.value && shape != IconShape.DEFAULT) clipTo(it, shape) else it
            }
        }
    }

    private fun packBitmap(pack: String, name: String?, s: Int): Bitmap? =
        name?.let { drawable(IconSrc.Pack(pack, it))?.toBitmap(s, s, Bitmap.Config.ARGB_8888) }

    private val looks = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<PackLook>>()

    /** Two-tone look of a pack (plate + glyph colours, shape), learned from its icons; null if it has none. */
    fun packLook(pack: LoadedPack): PackLook? = looks.getOrPut("${pack.packageName}@${pack.versionCode}#${forcedNight.get()}") {
        val s = 64
        val samples = pack.byComponent.values.distinct().take(40).mapNotNull { name ->
            drawable(IconSrc.Pack(pack.packageName, name))?.let { d ->
                runCatching {
                    val b = d.toBitmap(s, s, Bitmap.Config.ARGB_8888)
                    IntArray(s * s).also { b.getPixels(it, 0, s, 0, 0, s, s) }
                }.getOrNull()
            }
        }
        java.util.Optional.ofNullable(PackLookAnalyzer.analyze(samples, s))
    }.orElse(null)

    /**
     * The app's single-colour symbol (the layer Android 13 uses for themed icons), else its
     * adaptive foreground when that is a symbol and not a full picture. Only alpha is used.
     */
    private fun glyphBitmap(app: IconSrc.App, s: Int): Bitmap? {
        val d = drawable(app) ?: return null
        if (d is AdaptiveIconDrawable) {
            val mono = if (Build.VERSION.SDK_INT >= 33) d.monochrome else null
            val layer = mono ?: d.foreground
            if (layer != null) {
                val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val inset = s / 4
                layer.setBounds(-inset, -inset, s + inset, s + inset)
                layer.draw(Canvas(out))
                if (mono != null) return out
                // A foreground covering most of the icon is a picture, not a symbol.
                val px = IntArray(s * s).also { out.getPixels(it, 0, s, 0, 0, s, s) }
                if (px.count { (it ushr 24) > 128 } <= s * s * 0.45) return out
            }
        }
        // No symbol layer: cut the logo out of the normal icon (what stands out from its background).
        val full = render(app, s, IconShape.SQUARE) ?: return null
        val px = IntArray(s * s).also { full.getPixels(it, 0, s, 0, 0, s, s) }
        val mask = PackLookAnalyzer.logoMask(px, s) ?: return null
        val alpha = IntArray(s * s) { if (mask[it].toInt() != 0) 0xFF000000.toInt() else 0 }
        return Bitmap.createBitmap(alpha, s, s, Bitmap.Config.ARGB_8888)
    }

    /** Keeps only the part of [b] inside [shape]. */
    private fun clipTo(b: Bitmap, shape: IconShape): Bitmap {
        if (shape == IconShape.DEFAULT) return b
        val s = b.width
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawPath(shape.path(s.toFloat()), p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        c.drawBitmap(b, 0f, 0f, p)
        return out
    }

    /**
     * An icon for an app the pack doesn't cover, made to look like the pack:
     *  - two-tone packs (e.g. a white glyph on a dark circle): a plate in the pack's colour and
     *    shape (or the theme's shape) with the app's own symbol in the pack's glyph colour;
     *  - packs with a style kit: the app icon scaled, masked and placed on the pack's back plate;
     *  - otherwise the app icon in the theme's shape.
     */
    private fun renderGenerated(src: IconSrc.Generated, s: Int, shape: IconShape): Bitmap? {
        val pack = kotlinx.coroutines.runBlocking { packs.load(src.pack) }
        val appSrc = IconSrc.App(src.packageName, src.activityName)
        val look = pack?.let { packLook(it) }
        if (look != null) {
            val plateShape = if (shape != IconShape.DEFAULT) shape else look.shape
            // A monochrome layer's symbol spans ~61% of the icon at normal size.
            val gs = (s * look.glyphSize / 0.61f).toInt().coerceIn(s / 3, (s * 1.3f).toInt())
            val glyph = glyphBitmap(appSrc, gs)
            if (glyph != null) {
                val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                val plateSide = if (shape != IconShape.DEFAULT) s.toFloat() else s * look.plateSize
                val inset = (s - plateSide) / 2f
                c.save(); c.translate(inset, inset)
                c.drawPath(plateShape.path(plateSide), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.plate })
                c.restore()
                val gp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = android.graphics.PorterDuffColorFilter(look.glyph, PorterDuff.Mode.SRC_IN)
                }
                c.drawBitmap(glyph, (s - gs) / 2f, (s - gs) / 2f, gp)
                return out
            }
        }
        if (pack == null || !pack.hasStyle) return render(appSrc, s, if (shape == IconShape.DEFAULT) IconShape.SQUIRCLE else shape)
        return renderStyleKit(src, pack, appSrc, s, shape)?.let { clipTo(it, shape) }
    }

    /** Launcher-style fallback: app icon scaled, cut by the mask, on the back plate, overlay on top. */
    private fun renderStyleKit(src: IconSrc.Generated, pack: LoadedPack, appSrc: IconSrc.App, s: Int, shape: IconShape): Bitmap? {
        val scale = pack.scale
        val inner = (s * scale).toInt().coerceAtLeast(8)
        val icon = render(appSrc, inner, shape) ?: return null
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val off = (s - inner) / 2f
        c.drawBitmap(icon, off, off, null)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        packBitmap(src.pack, pack.mask, s)?.let {
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT); c.drawBitmap(it, 0f, 0f, paint)
        }
        if (pack.backs.isNotEmpty()) {
            val back = pack.backs[Math.floorMod(src.packageName.hashCode(), pack.backs.size)]
            packBitmap(src.pack, back, s)?.let {
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER); c.drawBitmap(it, 0f, 0f, paint)
            }
        }
        packBitmap(src.pack, pack.upon, s)?.let { paint.xfermode = null; c.drawBitmap(it, 0f, 0f, paint) }
        return out
    }

    /** Resized icon, optionally on a plate in the theme's shape. */
    private fun renderAdjusted(src: IconSrc.Adjusted, s: Int, shape: IconShape): Bitmap? {
        val inner = (s * src.scale).toInt().coerceIn(8, s * 2)
        val icon = render(src.base, inner, shape) ?: return null
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        src.bgColor?.let { color ->
            val plate = if (shape == IconShape.DEFAULT) IconShape.SQUIRCLE else shape
            c.drawPath(plate.path(s.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
        val off = (s - inner) / 2f
        c.drawBitmap(icon, off, off, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * Draws an adaptive icon's own layers into [shape] instead of the launcher's mask.
     * Layers are 108dp with the visible 72dp in the middle, so they extend 1/4 beyond each edge.
     */
    private fun renderAdaptive(d: AdaptiveIconDrawable, s: Int, shape: IconShape): Bitmap {
        val layers = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layers)
        val inset = s / 4
        listOfNotNull(d.background, d.foreground).forEach {
            it.setBounds(-inset, -inset, s + inset, s + inset)
            it.draw(lc)
        }
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawPath(shape.path(s.toFloat()), paint)                  // anti-aliased mask
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)  // keep layers only inside it
        c.drawBitmap(layers, 0f, 0f, paint)
        layers.recycle()
        return out
    }

    @SuppressLint("DiscouragedApi")
    fun drawable(src: IconSrc): Drawable? = when (src) {
        is IconSrc.App -> try {
            pm.getActivityIcon(ComponentName(src.packageName, src.activityName))
        } catch (_: Exception) {
            runCatching { pm.getApplicationIcon(src.packageName) }.getOrNull()
        }
        is IconSrc.Pack -> {
            val res = packs.resourcesFor(src.pack, forcedNight.get())
            val id = packs.cached(src.pack)?.drawableId(src.drawable)
                ?: res?.getIdentifier(src.drawable, "drawable", src.pack)?.takeIf { it != 0 }
                ?: res?.getIdentifier(src.drawable, "mipmap", src.pack)
                ?: 0
            if (res == null || id == 0) null else ResourcesCompat.getDrawable(res, id, null)
        }
        is IconSrc.FileImage -> File(src.path).takeIf { it.exists() }?.let {
            android.graphics.drawable.BitmapDrawable(context.resources, BitmapFactory.decodeFile(it.path))
        }
        is IconSrc.Generated, is IconSrc.Adjusted -> render(src, 192, IconShape.DEFAULT)
            ?.let { android.graphics.drawable.BitmapDrawable(context.resources, it) }
    }

    fun clear() = cache.evictAll()
}

/** Shapes adaptive icons can be cut into (launchers on ColorOS/OxygenOS can't change it). */
enum class IconShape(val label: String) {
    DEFAULT("Launcher default"),
    CIRCLE("Circle"),
    SQUIRCLE("Squircle"),
    ROUNDED("Rounded square"),
    TEARDROP("Teardrop"),
    SQUARE("Square");

    fun path(s: Float): Path = Path().apply {
        val r = RectF(0f, 0f, s, s)
        when (this@IconShape) {
            DEFAULT, SQUARE -> addRect(r, Path.Direction.CW)
            CIRCLE -> addOval(r, Path.Direction.CW)
            ROUNDED -> addRoundRect(r, s * 0.22f, s * 0.22f, Path.Direction.CW)
            TEARDROP -> {
                val big = s / 2; val small = s * 0.12f
                // corners: top-left, top-right, bottom-right (small), bottom-left
                addRoundRect(r, floatArrayOf(big, big, big, big, small, small, big, big), Path.Direction.CW)
            }
            SQUIRCLE -> {
                // superellipse |x|^n + |y|^n = 1
                val n = 4.0; val h = s / 2
                for (i in 0..360) {
                    val t = Math.toRadians(i.toDouble())
                    val c = Math.cos(t); val sn = Math.sin(t)
                    val x = h + h * Math.signum(c) * Math.pow(Math.abs(c), 2 / n)
                    val y = h + h * Math.signum(sn) * Math.pow(Math.abs(sn), 2 / n)
                    if (i == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
                }
                close()
            }
        }
    }

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

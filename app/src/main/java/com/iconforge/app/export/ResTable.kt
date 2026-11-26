package com.iconforge.app.export

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/*
 * Resource tables beyond "a list of PNGs":
 *  - OutTable   writes a resources.arsc with any types, configs and simple values.
 *  - SourceRes  reads another app's resources.arsc (base + split APKs).
 *  - LiveCopier copies one drawable from an installed icon pack, with everything it
 *               references (colours, layers, vectors), into a LiveBundle. Framework
 *               references (e.g. @android:color/system_accent1_100, the Material You
 *               palette) are kept, so the copied icon keeps following the wallpaper.
 * Pure Kotlin: tested on the PC against packs built with aapt2.
 */

// ====================================================================== writing

/** A simple Res_value, or a string (file path or plain string). */
sealed interface OutVal {
    data class Simple(val type: Int, val data: Int) : OutVal
    data class Str(val value: String) : OutVal
}

class OutTable(private val packageName: String) {
    inner class TypeBuilder(val name: String, val id: Int) {
        internal val names = ArrayList<String>()
        internal val values = ArrayList<List<Pair<ByteArray, OutVal>>>()

        /** Adds (or reserves, with no values yet) an entry; returns its resource id. */
        fun add(entry: String, vals: List<Pair<ByteArray, OutVal>> = emptyList()): Int {
            names += entry; values += vals
            return resId(names.size - 1)
        }

        fun set(resId: Int, vals: List<Pair<ByteArray, OutVal>>) { values[resId and 0xFFFF] = vals }
        fun resId(index: Int) = (0x7f shl 24) or (id shl 16) or index
        val size get() = names.size
    }

    private val types = LinkedHashMap<String, TypeBuilder>()
    fun type(name: String): TypeBuilder = types.getOrPut(name) { TypeBuilder(name, types.size + 1) }

    fun encode(): ByteArray {
        val strings = ArrayList<String>(); val strIdx = HashMap<String, Int>()
        fun str(s: String) = strIdx.getOrPut(s) { strings += s; strings.size - 1 }
        val keys = ArrayList<String>(); val keyIdx = HashMap<String, Int>()
        fun key(s: String) = keyIdx.getOrPut(s) { keys += s; keys.size - 1 }

        val chunks = LeBuffer()
        for (t in types.values) {
            val n = t.size
            // typeSpec: entries with several configs get all "varies by" bits.
            val spec = LeBuffer().u16(0x0202).u16(16).u32(16 + 4 * n).u8(t.id).u8(0).u16(0).u32(n)
            for (v in t.values) spec.u32(if (v.size > 1) 0x0003FFFF else 0)
            chunks.bytes(spec.toByteArray())

            val configs = LinkedHashMap<String, ByteArray>()
            t.values.forEach { vs -> vs.forEach { (c, _) -> configs.putIfAbsent(c.contentToString(), c) } }
            for ((ck, cfg) in configs) {
                val headerSize = 20 + cfg.size
                val body = LeBuffer()
                val offsets = IntArray(n) { -1 }
                for (i in 0 until n) {
                    val v = t.values[i].firstOrNull { it.first.contentToString() == ck }?.second ?: continue
                    offsets[i] = body.size
                    body.u16(8).u16(0).u32(key(t.names[i]))
                    when (v) {
                        is OutVal.Simple -> body.u16(8).u8(0).u8(v.type).u32(v.data)
                        is OutVal.Str -> body.u16(8).u8(0).u8(0x03).u32(str(v.value))
                    }
                }
                val bodyBytes = body.toByteArray()
                val type = LeBuffer().u16(0x0201).u16(headerSize).u32(headerSize + 4 * n + bodyBytes.size)
                    .u8(t.id).u8(0).u16(0).u32(n).u32(headerSize + 4 * n).bytes(cfg)
                offsets.forEach { type.u32(it) }
                chunks.bytes(type.bytes(bodyBytes).toByteArray())
            }
        }
        // Names referenced by entries with no values still need key strings.
        types.values.forEach { t -> t.names.forEach { key(it) } }

        val typePool = encodeStringPool(types.keys.toList())
        val keyPool = encodeStringPool(keys)
        val typeBytes = chunks.toByteArray()
        val pkgHeader = 288
        val pkg = LeBuffer().u16(0x0200).u16(pkgHeader)
            .u32(pkgHeader + typePool.size + keyPool.size + typeBytes.size).u32(0x7f)
        for (i in 0 until 128) pkg.u16(if (i < packageName.length && i < 127) packageName[i].code else 0)
        pkg.u32(pkgHeader).u32(types.size).u32(pkgHeader + typePool.size).u32(keys.size).u32(0)
        pkg.bytes(typePool).bytes(keyPool).bytes(typeBytes)
        val pkgBytes = pkg.toByteArray()
        val pool = encodeStringPool(strings)
        return LeBuffer().u16(0x0002).u16(12).u32(12 + pool.size + pkgBytes.size).u32(1)
            .bytes(pool).bytes(pkgBytes).toByteArray()
    }

    companion object {
        /** ResTable_config: default (any), nodpi, and nodpi + night (dark theme). uiMode is byte 29. */
        fun config(density: Int = 0, night: Boolean = false): ByteArray =
            ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 64).putShort(14, density.toShort())
                .put(29, if (night) 0x20.toByte() else 0).array()
        val DEFAULT_CONFIG: ByteArray get() = config()
        val NODPI_CONFIG: ByteArray get() = config(0xFFFF)
        val NODPI_NIGHT_CONFIG: ByteArray get() = config(0xFFFF, night = true)
    }
}

// ====================================================================== reading

private class Le(val b: ByteArray) {
    fun u8(o: Int) = b[o].toInt() and 0xFF
    fun u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
    fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
}

private fun readStringPool(le: Le, off: Int): List<String> {
    val headerSize = le.u16(off + 2)
    val count = le.u32(off + 8)
    val utf8 = le.u32(off + 16) and 0x100 != 0
    val stringsStart = le.u32(off + 20)
    return List(count) { i ->
        var o = off + stringsStart + le.u32(off + headerSize + 4 * i)
        if (utf8) {
            var l = le.u8(o++); if (l and 0x80 != 0) o++                 // utf-16 length (skipped)
            l = le.u8(o++); if (l and 0x80 != 0) l = ((l and 0x7F) shl 8) or le.u8(o++)
            String(le.b, o, l, Charsets.UTF_8)
        } else {
            var l = le.u16(o); o += 2
            if (l and 0x8000 != 0) { l = ((l and 0x7FFF) shl 16) or le.u16(o); o += 2 }
            CharArray(l) { le.u16(o + 2 * it).toChar() }.concatToString()
        }
    }
}

/** One value of a resource in one configuration. [complex] = bag (style, array…), unsupported. */
class SrcValue(val config: ByteArray, val type: Int, val data: Int, val str: String?, val complex: Boolean)

/** Resources of an installed app, merged from its base and split APKs. */
class SourceRes(private val apks: List<File>) : AutoCloseable {
    val names = HashMap<Int, Pair<String, String>>()       // id → (type, entry name)
    val values = HashMap<Int, MutableList<SrcValue>>()
    private val byName = HashMap<String, Int>()             // "type/name" → id
    private val zips = apks.mapNotNull { runCatching { ZipFile(it) }.getOrNull() }

    init {
        for (z in zips) {
            val e = z.getEntry("resources.arsc") ?: continue
            parse(z.getInputStream(e).use { it.readBytes() })
        }
    }

    fun id(type: String, name: String): Int? = byName["$type/$name"]

    fun file(path: String): ByteArray? = zips.firstNotNullOfOrNull { z ->
        z.getEntry(path)?.let { e -> z.getInputStream(e).use { it.readBytes() } }
    }

    override fun close() { zips.forEach { runCatching { it.close() } } }

    private fun parse(bytes: ByteArray) {
        val le = Le(bytes)
        if (le.u16(0) != 0x0002) return
        var globals: List<String> = emptyList()
        var c = le.u16(2)
        while (c + 8 <= bytes.size) {
            val type = le.u16(c); val size = le.u32(c + 4)
            if (size <= 0) break
            when (type) {
                0x0001 -> globals = readStringPool(le, c)
                0x0200 -> parsePackage(le, c, size, globals)
            }
            c += size
        }
    }

    private fun parsePackage(le: Le, p: Int, size: Int, globals: List<String>) {
        val pkgId = le.u32(p + 8)
        val headerSize = le.u16(p + 2)
        val typeStrings = readStringPool(le, p + le.u32(p + 268))
        val keyStrings = readStringPool(le, p + le.u32(p + 276))
        val typeIdOffset = if (headerSize >= 288) le.u32(p + 284) else 0
        var c = p + headerSize
        while (c + 8 <= p + size) {
            val ctype = le.u16(c); val csize = le.u32(c + 4)
            if (csize <= 0) break
            if (ctype == 0x0201) parseType(le, c, pkgId, typeStrings, keyStrings, typeIdOffset, globals)
            c += csize
        }
    }

    private fun parseType(le: Le, c: Int, pkgId: Int, typeStrings: List<String>, keys: List<String>, typeIdOffset: Int, globals: List<String>) {
        val headerSize = le.u16(c + 2)
        val typeId = le.u8(c + 8)
        val flags = le.u8(c + 9)
        val count = le.u32(c + 12)
        val entriesStart = le.u32(c + 16)
        val cfgSize = le.u32(c + 20)
        val config = le.b.copyOfRange(c + 20, c + 20 + cfgSize)
        val typeName = typeStrings.getOrNull(typeId - 1 - typeIdOffset) ?: return
        val o = c + headerSize

        fun entry(index: Int, off: Int) {
            val e = c + entriesStart + off
            val eflags = le.u16(e + 2)
            val keyIndex: Int
            val v: SrcValue
            if (eflags and 0x8 != 0) {                          // compact entry
                keyIndex = le.u16(e)
                val dt = (eflags ushr 8) and 0xFF; val data = le.u32(e + 4)
                v = SrcValue(config, dt, data, if (dt == 0x03) globals.getOrNull(data) else null, false)
            } else {
                keyIndex = le.u32(e + 4)
                v = if (eflags and 0x1 != 0) SrcValue(config, 0, 0, null, true) else {
                    val r = e + le.u16(e)
                    val dt = le.u8(r + 3); val data = le.u32(r + 4)
                    SrcValue(config, dt, data, if (dt == 0x03) globals.getOrNull(data) else null, false)
                }
            }
            val id = (pkgId shl 24) or (typeId shl 16) or index
            val name = keys.getOrNull(keyIndex) ?: return
            names[id] = typeName to name
            byName.putIfAbsent("$typeName/$name", id)
            values.getOrPut(id) { ArrayList() } += v
        }

        when {
            flags and 0x01 != 0 -> for (k in 0 until count) entry(le.u16(o + 4 * k), le.u16(o + 4 * k + 2) * 4) // sparse
            flags and 0x02 != 0 -> for (i in 0 until count) {                                                 // 16-bit offsets
                val v = le.u16(o + 2 * i); if (v != 0xFFFF) entry(i, v * 4)
            }
            else -> for (i in 0 until count) { val v = le.u32(o + 4 * i); if (v != -1) entry(i, v) }
        }
    }
}

/** Reads compiled XML back into a tree (namespaces, element tree with typed attributes). */
fun parseAxml(bytes: ByteArray): Pair<List<Pair<String, String>>, XNode> {
    val le = Le(bytes)
    require(bytes.size >= 8 && le.u16(0) == 0x0003) { "not compiled xml" }
    var strings: List<String> = emptyList()
    var resIds = IntArray(0)
    val namespaces = ArrayList<Pair<String, String>>()
    class Open(val name: String, val attrs: List<XAttr>) { val children = ArrayList<XNode>() }
    val stack = ArrayList<Open>()
    var root: XNode? = null
    var c = le.u16(2)
    while (c + 8 <= bytes.size) {
        val type = le.u16(c); val size = le.u32(c + 4)
        if (size <= 0) break
        when (type) {
            0x0001 -> strings = readStringPool(le, c)
            0x0180 -> resIds = IntArray((size - 8) / 4) { le.u32(c + 8 + 4 * it) }
            0x0100 -> namespaces += strings[le.u32(c + 16)] to strings[le.u32(c + 20)]
            0x0102 -> {
                val attrStart = le.u16(c + 24); val attrSize = le.u16(c + 26); val count = le.u16(c + 28)
                val attrs = List(count) { i ->
                    val a = c + 16 + attrStart + i * attrSize
                    val ns = le.u32(a).takeIf { it != -1 }?.let { strings[it] }
                    val nameIdx = le.u32(a + 4)
                    val dt = le.u8(a + 15); val data = le.u32(a + 16)
                    XAttr(ns, strings[nameIdx], resIds.getOrElse(nameIdx) { 0 }, dt, data,
                        if (dt == 0x03) strings.getOrNull(data) else null)
                }
                stack += Open(strings[le.u32(c + 20)], attrs)
            }
            0x0103 -> {
                val o = stack.removeAt(stack.size - 1)
                val node = XNode(o.name, o.attrs, o.children)
                if (stack.isEmpty()) root = node else stack.last().children += node
            }
        }
        c += size
    }
    return namespaces to (root ?: error("empty xml"))
}

/** Icon shapes as SVG path data on a 100 × 100 canvas (same shapes as IconShape). */
object ShapePaths {
    private val memo = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Complex fraction (radix 0p23) for an inset showing the foreground at [scale] (1 = -25%). */
    fun insetFraction(scale: Float): Int {
        val f = -(1.5 * scale - 1) / 2
        return ((Math.round(f * (1 shl 23)).toInt()) shl 8) or 0x30
    }

    /** Outline of [shape] as path data in a 100x100 box; null for DEFAULT (the launcher decides). */
    fun svg(shape: String): String? = memo[shape] ?: build(shape)?.also { memo[shape] = it }

    private fun build(shape: String): String? = when (shape) {
        "CIRCLE" -> "M50,0A50,50 0,1 1,50,100A50,50 0,1 1,50,0Z"
        "SQUARE" -> "M0,0H100V100H0Z"
        "ROUNDED" -> "M22,0H78A22,22 0,0 1,100,22V78A22,22 0,0 1,78,100H22A22,22 0,0 1,0,78V22A22,22 0,0 1,22,0Z"
        "TEARDROP" -> "M50,0A50,50 0,0 1,100,50V88A12,12 0,0 1,88,100H50A50,50 0,0 1,0,50A50,50 0,0 1,50,0Z"
        "SQUIRCLE" -> buildString {
            // superellipse |x|^4 + |y|^4 = 1
            for (i in 0 until 360 step 3) {
                val t = Math.toRadians(i.toDouble()); val c = Math.cos(t); val s = Math.sin(t)
                val x = 50 + 50 * Math.signum(c) * Math.pow(Math.abs(c), 0.5)
                val y = 50 + 50 * Math.signum(s) * Math.pow(Math.abs(s), 0.5)
                append(if (i == 0) "M" else "L").append("%.2f,%.2f".format(java.util.Locale.ROOT, x, y))
            }
            append("Z")
        }
        "HEXAGON" -> fit(roundedPolygon(6, 0.18))
        "FLOWER" -> fit(polar(360) { t -> 1 + 0.065 * Math.cos(8 * t) })
        "LEAF" -> "M50,0H90A10,10 0,0 1,100,10V50A50,50 0,0 1,50,100H10A10,10 0,0 1,0,90V50A50,50 0,0 1,50,0Z"
        "PEBBLE" -> fit(polar(360) { t -> 1 + 0.07 * Math.cos(2 * t + 0.7) + 0.035 * Math.cos(3 * t + 2.1) })
        else -> null                                     // DEFAULT: the launcher decides
    }

    /** Closed outline from a radius function r(angle), sampled every [n]th of a turn. */
    private fun polar(n: Int, r: (Double) -> Double): List<Pair<Double, Double>> = (0 until n).map { i ->
        val t = 2 * Math.PI * i / n; val k = r(t)
        Math.cos(t) * k to Math.sin(t) * k
    }

    /** Regular polygon (point at the top) with corners rounded by [round] of each side. */
    private fun roundedPolygon(sides: Int, round: Double): List<Pair<Double, Double>> {
        val v = (0 until sides).map { i -> val t = -Math.PI / 2 + 2 * Math.PI * i / sides; Math.cos(t) to Math.sin(t) }
        val out = ArrayList<Pair<Double, Double>>()
        for (i in v.indices) {
            val p = v[(i + sides - 1) % sides]; val c = v[i]; val q = v[(i + 1) % sides]
            // Corner as a quadratic curve, sampled: from (c→p) to (c→q) at [round] of the side.
            val a = (c.first + (p.first - c.first) * round) to (c.second + (p.second - c.second) * round)
            val b = (c.first + (q.first - c.first) * round) to (c.second + (q.second - c.second) * round)
            for (j in 0..6) {
                val u = j / 6.0; val w0 = (1 - u) * (1 - u); val w1 = 2 * u * (1 - u); val w2 = u * u
                out += (w0 * a.first + w1 * c.first + w2 * b.first) to (w0 * a.second + w1 * c.second + w2 * b.second)
            }
        }
        return out
    }

    /** Scales points to fill a 100x100 box (aspect kept, centred) and writes them as path data. */
    private fun fit(pts: List<Pair<Double, Double>>): String {
        val x0 = pts.minOf { it.first }; val x1 = pts.maxOf { it.first }
        val y0 = pts.minOf { it.second }; val y1 = pts.maxOf { it.second }
        val k = 100 / maxOf(x1 - x0, y1 - y0)
        val dx = (100 - (x1 - x0) * k) / 2; val dy = (100 - (y1 - y0) * k) / 2
        return buildString {
            pts.forEachIndexed { i, (x, y) ->
                append(if (i == 0) "M" else "L")
                append("%.2f,%.2f".format(java.util.Locale.ROOT, (x - x0) * k + dx, (y - y0) * k + dy))
            }
            append("Z")
        }
    }
}

// ====================================================================== copying

data class LiveKey(val type: String, val name: String)

sealed interface LiveVal {
    data class Fixed(val type: Int, val data: Int) : LiveVal
    data class Ref(val key: LiveKey) : LiveVal
    data class Str(val value: String) : LiveVal
}

/** Compiled XML whose resource references are filled in once ids are known. */
class XmlTemplate(val bytes: ByteArray, val patches: List<Pair<Int, LiveKey>>)

/** Resources copied from icon packs, waiting to be placed in the exported table. */
class LiveBundle {
    val entries = LinkedHashMap<LiveKey, List<Pair<ByteArray, LiveVal>>>()
    val files = LinkedHashMap<String, Any>()                   // path → ByteArray | XmlTemplate
    /** appfilter drawable name → copied resource. */
    val aliases = LinkedHashMap<String, LiveKey>()

    val isEmpty get() = aliases.isEmpty()

    internal fun merge(o: LiveBundle) { entries.putAll(o.entries); files.putAll(o.files); aliases.putAll(o.aliases) }

    /** Places everything into [table] and returns the files to add to the APK. */
    fun install(table: OutTable): Map<String, ByteArray> {
        val ids = HashMap<LiveKey, Int>()
        for (k in entries.keys) ids[k] = table.type(k.type).add(k.name)
        for ((k, vals) in entries) {
            table.type(k.type).set(ids.getValue(k), vals.map { (cfg, v) ->
                cfg to when (v) {
                    is LiveVal.Fixed -> OutVal.Simple(v.type, v.data)
                    is LiveVal.Ref -> OutVal.Simple(0x01, ids.getValue(v.key))
                    is LiveVal.Str -> OutVal.Str(v.value)
                }
            })
        }
        val drawable = table.type("drawable")
        for ((name, key) in aliases) drawable.add(name, listOf(OutTable.DEFAULT_CONFIG to OutVal.Simple(0x01, ids.getValue(key))))
        return files.mapValues { (_, f) ->
            when (f) {
                is XmlTemplate -> f.bytes.copyOf().also { b ->
                    val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                    f.patches.forEach { (off, key) -> bb.putInt(off, ids.getValue(key)); b[off - 1] = 0x01 }
                }
                else -> f as ByteArray
            }
        }
    }
}

/**
 * Copies drawables of one installed pack. Only worth it for XML icons (vectors /
 * adaptive icons that reference colours); bitmap icons stay rasterised.
 */
class LiveCopier(private val src: SourceRes, packageName: String) {
    private class Unsupported(msg: String) : Exception(msg)

    private val prefix = "lv" + Integer.toHexString(packageName.hashCode() and 0xFFFFFF) + "_"
    private val fileTypes = setOf("drawable", "mipmap", "color", "xml", "anim", "animator", "interpolator")

    /** Shape (SVG path, 100 × 100) adaptive icons are being rewritten into during [collect]. */
    private var reshape: String? = null
    /** Size of the foreground inside [reshape] (1 = as designed). */
    private var reshapeScale = 1f

    /**
     * Copies [drawable] into [into] under the appfilter name [alias]. False = not XML / not copyable.
     *
     * [shape] (from [ShapePaths]; null = launcher default): the launcher picks the shape of
     * adaptive icons itself, so with a theme shape they are rewritten into ordinary drawables
     * already cut to that shape — still live, as the colours stay references. [shapeAll]: plain
     * (non-adaptive) XML icons should be cut too, which can't stay live, so they are refused.
     */
    fun collect(drawable: String, alias: String, into: LiveBundle, shape: String? = null, shapeAll: Boolean = false,
                scale: Float = 1f): Boolean {
        val id = src.id("drawable", drawable) ?: src.id("mipmap", drawable) ?: return false
        val xmlFiles = src.values[id].orEmpty().mapNotNull { v -> v.str?.takeIf { it.endsWith(".xml") } }
        if (xmlFiles.isEmpty()) return false
        if (shape != null && shapeAll && xmlFiles.any { f -> src.file(f)?.let { rootTag(it) } != "adaptive-icon" }) return false
        val tmp = LiveBundle()
        reshape = shape
        reshapeScale = if (shape != null) scale else 1f
        return try {
            val key = copy(id, tmp, into)
            tmp.aliases[alias] = key
            into.merge(tmp)
            true
        } catch (_: Unsupported) {
            false
        } catch (_: RuntimeException) {
            false
        } finally {
            reshape = null; reshapeScale = 1f
        }
    }

    /**
     * Rewrites an adaptive icon into a layer list the launcher draws as it is:
     *   1. the background colour filling [shape] (a vector, so colour references stay live);
     *   2. the foreground, enlarged by a -25% inset so its visible 72/108 middle fills the icon
     *      (exactly what an adaptive icon shows inside the launcher's mask).
     * Only colour backgrounds are supported; anything else stays a picture.
     */
    private fun reshapeAdaptive(bytes: ByteArray, shape: String): ByteArray {
        val (_, root) = parseAxml(bytes)
        val bg = root.children.firstOrNull { it.name == "background" }
        val fg = root.children.firstOrNull { it.name == "foreground" } ?: throw Unsupported("no foreground")
        val ns = AndroidAttr.NS
        val items = ArrayList<XNode>()
        if (bg != null) {
            val colour = bg.attrs.firstOrNull { it.resId == ATTR_DRAWABLE }
                ?: bg.children.singleOrNull { it.name == "color" }?.attrs?.firstOrNull { it.resId == ATTR_COLOR }
                ?: throw Unsupported("background is not a colour")
            if (!isColour(colour)) throw Unsupported("background is not a colour")
            val dp108 = (108 shl 8) or 0x01                                   // complex dimension, dip
            val f100 = java.lang.Float.floatToRawIntBits(100f)
            items += XNode("item", children = listOf(XNode("vector", listOf(
                XAttr(ns, "width", 0x01010159, 0x05, dp108, null), XAttr(ns, "height", 0x01010155, 0x05, dp108, null),
                XAttr(ns, "viewportWidth", 0x01010402, 0x04, f100, null), XAttr(ns, "viewportHeight", 0x01010403, 0x04, f100, null),
            ), listOf(XNode("path", listOf(
                XAttr(ns, "fillColor", 0x01010404, colour.type, colour.data, colour.str),
                XAttr(ns, "pathData", 0x01010405, 0x03, 0, shape),
            ))))))
        }
        // The 108dp layer shows its middle 72dp: inset -25% at normal size, scaled about the centre.
        val inset = ShapePaths.insetFraction(reshapeScale)
        val insetAttrs = listOf(XAttr(ns, "inset", 0x010104b5, 0x06, inset, null)) +
            fg.attrs.filter { it.resId == ATTR_DRAWABLE }.map { XAttr(ns, "drawable", ATTR_DRAWABLE, it.type, it.data, it.str) }
        items += XNode("item", children = listOf(XNode("inset", insetAttrs, fg.children)))
        return encodeAxml(XNode("layer-list", children = items), listOf("android" to ns))
    }

    /** A colour value, or a reference to a colour (framework palette or the pack's own). */
    private fun isColour(a: XAttr): Boolean = when (a.type) {
        in 0x1c..0x1f -> true
        0x01, 0x07 -> when (a.data ushr 24) {
            0x01 -> (a.data ushr 16) and 0xFF == 0x06                         // @android:color/…
            0x7f -> when (src.names[a.data]?.first) {
                "color" -> true
                "drawable" -> src.values[a.data].orEmpty().let { v -> v.isNotEmpty() && v.all { it.type in 0x1c..0x1f } }
                else -> false
            }
            else -> false
        }
        else -> false
    }

    private companion object {
        const val ATTR_DRAWABLE = 0x01010199
        const val ATTR_COLOR = 0x010101a5
    }

    /** Name of the first element of compiled XML (e.g. "adaptive-icon", "vector"). */
    private fun rootTag(bytes: ByteArray): String? {
        val le = Le(bytes)
        if (bytes.size < 8 || le.u16(0) != 0x0003) return null
        var strings: List<String> = emptyList()
        var c = le.u16(2)
        while (c + 8 <= bytes.size) {
            val type = le.u16(c); val size = le.u32(c + 4)
            if (size <= 0) break
            when (type) {
                0x0001 -> strings = readStringPool(le, c)
                0x0102 -> return strings.getOrNull(le.u32(c + 20))
            }
            c += size
        }
        return null
    }

    private fun keyOf(id: Int): LiveKey {
        val (type, _) = src.names[id] ?: throw Unsupported("unknown id ${Integer.toHexString(id)}")
        // Shaped copies get their own names, so a shaped and a plain copy can't be mixed up.
        val shapeTag = reshape?.let { "s" + Integer.toHexString((it + reshapeScale).hashCode() and 0xFFFF) + "_" } ?: ""
        return LiveKey(type, prefix + shapeTag + Integer.toHexString(id))
    }

    private fun copy(id: Int, b: LiveBundle, done: LiveBundle): LiveKey {
        val key = keyOf(id)
        if (key in b.entries || key in done.entries) return key
        b.entries[key] = emptyList()                               // placeholder (cycles)
        val vals = src.values[id] ?: throw Unsupported("no values")
        b.entries[key] = vals.mapIndexed { i, v ->
            if (v.complex) throw Unsupported("bag resource")
            v.config to when (v.type) {
                0x01, 0x07 -> if (v.data ushr 24 == 0x7f) LiveVal.Ref(copy(v.data, b, done))
                              else if (v.data ushr 24 == 0x01) LiveVal.Fixed(0x01, v.data)
                              else throw Unsupported("foreign reference")
                0x02, 0x08 -> if (v.data ushr 24 == 0x01) LiveVal.Fixed(0x02, v.data) else throw Unsupported("app theme attribute")
                0x03 -> {
                    val s = v.str ?: throw Unsupported("string")
                    if (key.type in fileTypes && s.startsWith("res/")) LiveVal.Str(copyFile(s, key, i, b, done)) else LiveVal.Str(s)
                }
                else -> LiveVal.Fixed(v.type, v.data)
            }
        }
        return key
    }

    private fun copyFile(path: String, key: LiveKey, index: Int, b: LiveBundle, done: LiveBundle): String {
        val bytes = src.file(path) ?: throw Unsupported("missing $path")
        val ext = path.substringAfterLast('.', "bin")
        val out = "res/lv/${key.name}_$index.$ext"
        b.files[out] = if (ext == "xml") {
            val shape = reshape
            rewriteXml(if (shape != null && rootTag(bytes) == "adaptive-icon") reshapeAdaptive(bytes, shape) else bytes, b, done)
        } else bytes
        return out
    }

    /** Points the compiled XML's references at the copied resources. */
    private fun rewriteXml(bytes: ByteArray, b: LiveBundle, done: LiveBundle): XmlTemplate {
        val le = Le(bytes)
        if (le.u16(0) != 0x0003) throw Unsupported("not compiled xml")
        val patches = ArrayList<Pair<Int, LiveKey>>()
        var c = le.u16(2)
        while (c + 8 <= bytes.size) {
            val type = le.u16(c); val size = le.u32(c + 4)
            if (size <= 0) break
            when (type) {
                0x0180 -> for (i in 0 until (size - 8) / 4) {
                    if (le.u32(c + 8 + 4 * i) ushr 24 != 0x01) throw Unsupported("app-defined attribute")
                }
                0x0102 -> {
                    val attrStart = le.u16(c + 24); val attrSize = le.u16(c + 26); val count = le.u16(c + 28)
                    for (i in 0 until count) {
                        val a = c + 16 + attrStart + i * attrSize
                        val dt = le.u8(a + 15); val data = le.u32(a + 16)
                        when (dt) {
                            0x01, 0x07 -> if (data ushr 24 == 0x7f) patches += (a + 16) to copy(data, b, done)
                            0x02, 0x08 -> if (data ushr 24 != 0x01) throw Unsupported("app theme attribute in xml")
                        }
                    }
                }
            }
            c += size
        }
        return XmlTemplate(bytes, patches)
    }
}

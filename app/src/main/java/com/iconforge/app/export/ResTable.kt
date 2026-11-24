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
        /** ResTable_config: default (any) and nodpi. */
        fun config(density: Int = 0): ByteArray =
            ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 64).putShort(14, density.toShort()).array()
        val DEFAULT_CONFIG: ByteArray get() = config()
        val NODPI_CONFIG: ByteArray get() = config(0xFFFF)
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

    /** Copies [drawable] into [into] under the appfilter name [alias]. False = not XML / not copyable. */
    fun collect(drawable: String, alias: String, into: LiveBundle): Boolean {
        val id = src.id("drawable", drawable) ?: src.id("mipmap", drawable) ?: return false
        if (src.values[id].orEmpty().none { it.str?.endsWith(".xml") == true }) return false
        val tmp = LiveBundle()
        return try {
            val key = copy(id, tmp, into)
            tmp.aliases[alias] = key
            into.merge(tmp)
            true
        } catch (_: Unsupported) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun keyOf(id: Int): LiveKey {
        val (type, _) = src.names[id] ?: throw Unsupported("unknown id ${Integer.toHexString(id)}")
        return LiveKey(type, prefix + Integer.toHexString(id))
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
        b.files[out] = if (ext == "xml") rewriteXml(bytes, b, done) else bytes
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

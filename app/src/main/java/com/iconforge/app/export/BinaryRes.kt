package com.iconforge.app.export

import java.io.ByteArrayOutputStream

/**
 * Writers for Android's compiled XML ("AXML") used for AndroidManifest.xml and res/xml
 * files. The resources.arsc writer is OutTable in ResTable.kt.
 * Pure Kotlin (no Android APIs) so it can be unit-tested on the PC and checked with aapt2.
 */
internal class LeBuffer {
    private val out = ByteArrayOutputStream()
    val size: Int get() = out.size()
    fun u8(v: Int) = apply { out.write(v and 0xFF) }
    fun u16(v: Int) = apply { u8(v); u8(v ushr 8) }
    fun u32(v: Int) = apply { u16(v); u16(v ushr 16) }
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun padTo4() = apply { while (out.size() % 4 != 0) u8(0) }
    fun toByteArray(): ByteArray = out.toByteArray()
}

/** ResStringPool chunk, UTF-16 encoded, no styles. */
internal fun encodeStringPool(strings: List<String>): ByteArray {
    val data = LeBuffer()
    val offsets = IntArray(strings.size)
    strings.forEachIndexed { i, s ->
        offsets[i] = data.size
        val len = s.length
        if (len > 0x7FFF) {
            data.u16(((len ushr 16) and 0x7FFF) or 0x8000); data.u16(len and 0xFFFF)
        } else data.u16(len)
        s.forEach { data.u16(it.code) }
        data.u16(0)
    }
    data.padTo4()
    val headerSize = 28
    val stringsStart = if (strings.isEmpty()) 0 else headerSize + 4 * strings.size
    val total = headerSize + 4 * strings.size + data.size
    val b = LeBuffer().u16(0x0001).u16(headerSize).u32(total)
        .u32(strings.size).u32(0).u32(0).u32(stringsStart).u32(0)
    offsets.forEach { b.u32(it) }
    return b.bytes(data.toByteArray()).toByteArray()
}

// ---------------------------------------------------------------- compiled XML

object AndroidAttr {
    // Values of android.R.attr.* (stable public framework IDs).
    const val LABEL = 0x01010001
    const val ICON = 0x01010002
    const val NAME = 0x01010003
    const val HAS_CODE = 0x0101000c
    const val EXPORTED = 0x01010010
    const val MIN_SDK = 0x0101020c
    const val VERSION_CODE = 0x0101021b
    const val VERSION_NAME = 0x0101021c
    const val TARGET_SDK = 0x01010270
    const val NS = "http://schemas.android.com/apk/res/android"
}

class XAttr internal constructor(
    val ns: String?, val name: String, val resId: Int,
    internal val type: Int, internal val data: Int, internal val str: String?,
)

class XNode(val name: String, val attrs: List<XAttr> = emptyList(), val children: List<XNode> = emptyList())

object X {
    private const val TYPE_REF = 0x01
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT = 0x10
    private const val TYPE_BOOL = 0x12

    fun str(name: String, value: String) = XAttr(null, name, 0, TYPE_STRING, 0, value)
    fun aStr(name: String, resId: Int, value: String) = XAttr(AndroidAttr.NS, name, resId, TYPE_STRING, 0, value)
    fun aInt(name: String, resId: Int, value: Int) = XAttr(AndroidAttr.NS, name, resId, TYPE_INT, value, null)
    fun aBool(name: String, resId: Int, value: Boolean) =
        XAttr(AndroidAttr.NS, name, resId, TYPE_BOOL, if (value) -1 else 0, null)
    fun aRef(name: String, resId: Int, ref: Int) = XAttr(AndroidAttr.NS, name, resId, TYPE_REF, ref, null)
}

/** Encodes an element tree as Android binary XML. */
fun encodeAxml(root: XNode, namespaces: List<Pair<String, String>> = emptyList()): ByteArray {
    // Attribute names that carry a resource id must come first in the pool, in the
    // same order as the resource-map chunk.
    val resNames = LinkedHashMap<String, Int>()
    fun collect(n: XNode) {
        n.attrs.filter { it.resId != 0 }.forEach { resNames.putIfAbsent(it.name, it.resId) }
        n.children.forEach(::collect)
    }
    collect(root)
    val strings = ArrayList<String>(resNames.keys)
    val resIndex = resNames.keys.withIndex().associate { it.value to it.index }
    val other = HashMap<String, Int>()
    fun intern(s: String): Int = other.getOrPut(s) { strings += s; strings.size - 1 }

    val body = LeBuffer()
    var line = 1
    val nsIdx = namespaces.map { (prefix, uri) -> intern(prefix) to intern(uri) }
    nsIdx.forEach { (p, u) -> body.u16(0x0100).u16(16).u32(24).u32(line++).u32(-1).u32(p).u32(u) }

    fun write(n: XNode) {
        // Framework attribute lookup expects res-id attributes in ascending id order.
        val attrs = n.attrs.filter { it.resId == 0 } + n.attrs.filter { it.resId != 0 }.sortedBy { it.resId }
        val nameIdx = intern(n.name)
        body.u16(0x0102).u16(16).u32(36 + 20 * attrs.size).u32(line++).u32(-1)
            .u32(-1).u32(nameIdx).u16(0x14).u16(0x14).u16(attrs.size).u16(0).u16(0).u16(0)
        for (a in attrs) {
            val aName = if (a.resId != 0) resIndex.getValue(a.name) else intern(a.name)
            val aNs = a.ns?.let { intern(it) } ?: -1
            val strIdx = a.str?.let { intern(it) } ?: -1
            body.u32(aNs).u32(aName).u32(strIdx)
                .u16(8).u8(0).u8(a.type).u32(if (a.str != null) strIdx else a.data)
        }
        n.children.forEach(::write)
        body.u16(0x0103).u16(16).u32(24).u32(line++).u32(-1).u32(-1).u32(nameIdx)
    }
    write(root)
    nsIdx.reversed().forEach { (p, u) -> body.u16(0x0101).u16(16).u32(24).u32(line++).u32(-1).u32(p).u32(u) }

    val pool = encodeStringPool(strings)
    val resMap = if (resNames.isEmpty()) ByteArray(0) else LeBuffer().u16(0x0180).u16(8)
        .u32(8 + 4 * resNames.size).also { b -> resNames.values.forEach { b.u32(it) } }.toByteArray()
    val bodyBytes = body.toByteArray()
    return LeBuffer().u16(0x0003).u16(8).u32(8 + pool.size + resMap.size + bodyBytes.size)
        .bytes(pool).bytes(resMap).bytes(bodyBytes).toByteArray()
}

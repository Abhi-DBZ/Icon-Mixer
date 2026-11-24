package com.iconforge.app.export

import com.android.apksig.ApkSigner
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.Deflater

/** One bitmap in the generated pack. [resName] must be a valid resource name ([a-z][a-z0-9_]*). */
class PackIcon(val resName: String, val png: ByteArray)

/** "ComponentInfo{pkg/cls}" → icon. */
data class PackMapping(val packageName: String, val activityName: String, val resName: String)

/** Dynamic calendar icon: launchers show <prefix><day of month>. */
data class PackCalendar(val packageName: String, val activityName: String, val prefix: String)

class PackSpec(
    val packageName: String,
    val label: String,
    val versionCode: Int,
    val versionName: String,
    val icons: List<PackIcon>,
    val mappings: List<PackMapping>,
    /** Resource name of the pack's own launcher-list icon, or null. */
    val appIconRes: String?,
    /** Extra files copied verbatim into the APK (e.g. assets/iconforge/theme.json). */
    val extraFiles: Map<String, ByteArray> = emptyMap(),
    val calendars: List<PackCalendar> = emptyList(),
    /** Icons copied as live resources (Material You colours keep following the wallpaper). */
    val live: LiveBundle? = null,
)

/**
 * Builds an installable, resource-only icon-pack APK in the format launchers read
 * (Nova/ADW style: an activity answering the icon-pack intents + appfilter.xml +
 * drawables). No code (hasCode=false), so nothing in it ever runs.
 */
object ApkBuilder {

    /** Intents launchers use to discover icon packs (same set common Play-Store packs declare). */
    val PACK_ACTIONS = listOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.teslacoilsw.launcher.THEME",
        "com.gau.go.launcherex.theme",
        "com.fede.launcher.THEME_ICONPACK",
        "com.anddoes.launcher.THEME",
        "com.dlto.atom.launcher.THEME",
        "com.lge.launcher2.THEME",
        "com.gridappsinc.launcher.theme.apk_action",
        "net.oneplus.launcher.icons.ACTION_PICK_ICON",
        "ch.deletescape.lawnchair.ICONPACK",
    )
    val PACK_CATEGORIES = listOf(
        "android.intent.category.DEFAULT",
        "com.fede.launcher.THEME_ICONPACK",
        "com.anddoes.launcher.THEME",
        "com.teslacoilsw.launcher.THEME",
    )

    const val MIN_SDK = 26
    private const val TARGET_SDK = 34
    private const val ICON_DIR = "res/drawable-nodpi-v4/"

    fun appfilterText(spec: PackSpec): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")
        spec.mappings.forEach {
            append("    <item component=\"ComponentInfo{${esc(it.packageName)}/${esc(it.activityName)}}\" drawable=\"${it.resName}\" />\n")
        }
        spec.calendars.forEach {
            append("    <calendar component=\"ComponentInfo{${esc(it.packageName)}/${esc(it.activityName)}}\" prefix=\"${it.prefix}\" />\n")
        }
        append("</resources>\n")
    }

    /** Every drawable name in the pack (bitmaps + live icons), for launchers' icon pickers. */
    private fun allIconNames(spec: PackSpec) = spec.icons.map { it.resName } + (spec.live?.aliases?.keys ?: emptySet())

    fun drawableXmlText(spec: PackSpec): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <category title=\"All\" />\n")
        allIconNames(spec).forEach { append("    <item drawable=\"$it\" />\n") }
        append("</resources>\n")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    fun manifest(spec: PackSpec): ByteArray {
        val appIconId = spec.appIconRes?.let { name ->
            val i = spec.icons.indexOfFirst { it.resName == name }
            if (i >= 0) 0x7f010000 + i else null
        }
        val appAttrs = buildList {
            add(X.aStr("label", AndroidAttr.LABEL, spec.label))
            add(X.aBool("hasCode", AndroidAttr.HAS_CODE, false))
            appIconId?.let { add(X.aRef("icon", AndroidAttr.ICON, it)) }
        }
        val filter = XNode(
            "intent-filter",
            children = PACK_ACTIONS.map { XNode("action", listOf(X.aStr("name", AndroidAttr.NAME, it))) } +
                PACK_CATEGORIES.map { XNode("category", listOf(X.aStr("name", AndroidAttr.NAME, it))) },
        )
        val activity = XNode(
            "activity",
            listOf(
                X.aStr("name", AndroidAttr.NAME, spec.packageName + ".IconPack"),
                X.aStr("label", AndroidAttr.LABEL, spec.label),
                X.aBool("exported", AndroidAttr.EXPORTED, true),
            ),
            listOf(filter),
        )
        val root = XNode(
            "manifest",
            listOf(
                X.str("package", spec.packageName),
                X.aInt("versionCode", AndroidAttr.VERSION_CODE, spec.versionCode),
                X.aStr("versionName", AndroidAttr.VERSION_NAME, spec.versionName),
            ),
            listOf(
                XNode(
                    "uses-sdk",
                    listOf(
                        X.aInt("minSdkVersion", AndroidAttr.MIN_SDK, MIN_SDK),
                        X.aInt("targetSdkVersion", AndroidAttr.TARGET_SDK, TARGET_SDK),
                    ),
                ),
                XNode("application", appAttrs, listOf(activity)),
            ),
        )
        return encodeAxml(root, listOf("android" to AndroidAttr.NS))
    }

    private fun xmlTree(spec: PackSpec): Pair<XNode, XNode> {
        val appfilter = XNode("resources", children = spec.mappings.map {
            XNode("item", listOf(
                X.str("component", "ComponentInfo{${it.packageName}/${it.activityName}}"),
                X.str("drawable", it.resName),
            ))
        } + spec.calendars.map {
            XNode("calendar", listOf(
                X.str("component", "ComponentInfo{${it.packageName}/${it.activityName}}"),
                X.str("prefix", it.prefix),
            ))
        })
        val drawable = XNode("resources", children = listOf(XNode("category", listOf(X.str("title", "All")))) +
            allIconNames(spec).map { XNode("item", listOf(X.str("drawable", it))) })
        return appfilter to drawable
    }

    /** Writes the unsigned APK. */
    fun buildUnsigned(spec: PackSpec, out: File) {
        require(spec.icons.map { it.resName }.toSet().size == spec.icons.size) { "duplicate icon names" }
        // Type order fixes ids: drawable = 0x7f01xxxx with our bitmaps first (the manifest icon id relies on it).
        val table = OutTable(spec.packageName)
        val drawableType = table.type("drawable")
        spec.icons.forEach { drawableType.add(it.resName, listOf(OutTable.NODPI_CONFIG to OutVal.Str(ICON_DIR + it.resName + ".png"))) }
        val xmlType = table.type("xml")
        xmlType.add("appfilter", listOf(OutTable.DEFAULT_CONFIG to OutVal.Str("res/xml/appfilter.xml")))
        xmlType.add("drawable", listOf(OutTable.DEFAULT_CONFIG to OutVal.Str("res/xml/drawable.xml")))
        val liveFiles = spec.live?.install(table) ?: emptyMap()
        val (appfilterNode, drawableNode) = xmlTree(spec)
        out.outputStream().buffered().use { os ->
            val zip = ZipWriter(os)
            zip.add("AndroidManifest.xml", manifest(spec), compress = true)
            // resources.arsc must be stored uncompressed and 4-byte aligned (Android 11+).
            zip.add("resources.arsc", table.encode(), compress = false)
            zip.add("res/xml/appfilter.xml", encodeAxml(appfilterNode), compress = true)
            zip.add("res/xml/drawable.xml", encodeAxml(drawableNode), compress = true)
            // Plain-text copies: several launchers read these from assets instead.
            zip.add("assets/appfilter.xml", appfilterText(spec).toByteArray(), compress = true)
            zip.add("assets/drawable.xml", drawableXmlText(spec).toByteArray(), compress = true)
            spec.extraFiles.forEach { (path, data) -> zip.add(path, data, compress = true) }
            spec.icons.forEach { zip.add(ICON_DIR + it.resName + ".png", it.png, compress = false) }
            liveFiles.forEach { (path, data) -> zip.add(path, data, compress = path.endsWith(".xml")) }
            zip.finish()
        }
    }

    /** Signs with APK Signature Scheme v2 (Android 7+; packs need Android 8+ anyway). */
    fun sign(unsigned: File, signed: File, key: PrivateKey, certs: List<X509Certificate>) {
        val signer = ApkSigner.SignerConfig.Builder("ICONFORGE", key, certs).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(unsigned)
            .setOutputApk(signed)
            .setMinSdkVersion(MIN_SDK)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(false)
            .build()
            .sign()
    }

    /** Turns any text into a valid, readable resource name. */
    fun resName(raw: String): String {
        val s = raw.lowercase().replace(Regex("[^a-z0-9_]"), "_").replace(Regex("_+"), "_").trim('_')
        return when {
            s.isEmpty() -> "icon"
            s[0].isLetter() -> s
            else -> "i_$s"
        }.take(80)
    }
}

/** Minimal zip writer with control over alignment of stored entries. */
internal class ZipWriter(out: OutputStream) {
    private class Entry(val name: ByteArray, val crc: Int, val compSize: Int, val size: Int, val method: Int, val offset: Int)

    private val os = object : FilterOutputStream(out) {
        var count = 0
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }
    private val entries = ArrayList<Entry>()

    private fun le16(v: Int) { os.write(v and 0xFF); os.write((v ushr 8) and 0xFF) }
    private fun le32(v: Int) { le16(v); le16(v ushr 16) }

    fun add(name: String, data: ByteArray, compress: Boolean, align: Int = 4) {
        val crc = CRC32().apply { update(data) }.value.toInt()
        val payload = if (compress) deflate(data) else data
        val method = if (compress) 8 else 0
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val offset = os.count
        val pad = if (compress) 0 else (align - (offset + 30 + nameBytes.size) % align) % align
        le32(0x04034b50); le16(20); le16(0x0800); le16(method); le16(0); le16(0x0021)
        le32(crc); le32(payload.size); le32(data.size); le16(nameBytes.size); le16(pad)
        os.write(nameBytes); os.write(ByteArray(pad)); os.write(payload)
        entries += Entry(nameBytes, crc, payload.size, data.size, method, offset)
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(9, true)
        d.setInput(data); d.finish()
        val buf = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream(data.size / 2 + 64)
        while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
        d.end()
        return out.toByteArray()
    }

    fun finish() {
        val cdStart = os.count
        for (e in entries) {
            le32(0x02014b50); le16(20); le16(20); le16(0x0800); le16(e.method); le16(0); le16(0x0021)
            le32(e.crc); le32(e.compSize); le32(e.size); le16(e.name.size); le16(0); le16(0)
            le16(0); le16(0); le32(0); le32(e.offset); os.write(e.name)
        }
        val cdSize = os.count - cdStart
        le32(0x06054b50); le16(0); le16(0); le16(entries.size); le16(entries.size)
        le32(cdSize); le32(cdStart); le16(0)
        os.flush()
    }
}

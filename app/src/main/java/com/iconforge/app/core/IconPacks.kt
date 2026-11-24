package com.iconforge.app.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Process
import android.util.Xml
import com.iconforge.app.data.InstalledApp
import com.iconforge.app.export.ApkBuilder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/** Lists launchable apps through LauncherApps (what the home screen shows). */
class AppScanner(private val context: Context) {
    fun scan(): List<InstalledApp> {
        val la = context.getSystemService(LauncherApps::class.java)
        val now = System.currentTimeMillis()
        return la.getActivityList(null, Process.myUserHandle())
            .map {
                val cn = it.componentName
                val flags = it.applicationInfo.flags
                InstalledApp(
                    componentKey = key(cn.packageName, cn.className),
                    packageName = cn.packageName,
                    activityName = cn.className,
                    label = it.label?.toString()?.ifBlank { null } ?: cn.packageName,
                    isSystem = flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                    firstSeen = now,
                )
            }
            .distinctBy { it.componentKey }
    }

    companion object {
        fun key(pkg: String, cls: String) = "$pkg/$cls"
    }
}

/** Installed icon pack, as found by intent query (cheap). */
data class PackInfo(val packageName: String, val label: String, val versionCode: Long)

/** A fully parsed icon pack. */
class LoadedPack(
    val packageName: String,
    val label: String,
    val author: String,
    val versionCode: Long,
    val res: Resources,
    /** "pkg/cls" → drawable name. */
    val byComponent: Map<String, String>,
    /** pkg → drawable name (first component of that package). */
    val byPackage: Map<String, String>,
    /** Every usable drawable name, in the pack's own order. */
    val icons: List<String>,
    /** Style kit launchers use for unthemed apps: back plates, mask, overlay, icon scale. */
    val backs: List<String> = emptyList(),
    val mask: String? = null,
    val upon: String? = null,
    val scale: Float = 1f,
    /** "pkg/cls" → calendar prefix (icons <prefix>1 … <prefix>31). */
    val calendars: Map<String, String> = emptyMap(),
) {
    val hasStyle get() = backs.isNotEmpty() || mask != null || upon != null

    private val ids = ConcurrentHashMap<String, Int>()

    @SuppressLint("DiscouragedApi")
    fun drawableId(name: String): Int = ids.getOrPut(name) {
        res.getIdentifier(name, "drawable", packageName).takeIf { it != 0 }
            ?: res.getIdentifier(name, "mipmap", packageName)
    }

    /** Pack's icon for an activity: exact component first, then any activity of the same app. */
    fun match(packageName: String, activityName: String): String? =
        (byComponent[AppScanner.key(packageName, activityName)] ?: byPackage[packageName])
            ?.takeIf { drawableId(it) != 0 }
}

/** Finds and parses installed icon packs. Parsed packs are cached per version. */
class IconPacks(private val context: Context) {
    private val pm = context.packageManager
    private val cache = ConcurrentHashMap<String, LoadedPack>()
    private val resources = ConcurrentHashMap<String, Resources>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun detect(): List<PackInfo> {
        val found = LinkedHashMap<String, PackInfo>()
        val intents = ApkBuilder.PACK_ACTIONS.map { Intent(it) } + listOf(
            Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME"),
            Intent(Intent.ACTION_MAIN).addCategory("com.teslacoilsw.launcher.THEME"),
        )
        for (intent in intents) {
            val list = try { pm.queryIntentActivities(intent, PackageManager.GET_META_DATA) } catch (_: Exception) { emptyList() }
            for (ri in list) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == context.packageName || pkg in found) continue
                val info = try { pm.getPackageInfo(pkg, 0) } catch (_: Exception) { continue }
                val label = info.applicationInfo?.loadLabel(pm)?.toString() ?: pkg
                found[pkg] = PackInfo(pkg, label, info.longVersionCode)
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    fun resourcesFor(pkg: String): Resources? = resources[pkg] ?: try {
        pm.getResourcesForApplication(pkg).also { resources[pkg] = it }
    } catch (_: Exception) { null }

    fun cached(pkg: String): LoadedPack? = cache[pkg]

    suspend fun load(pkg: String): LoadedPack? = withContext(Dispatchers.IO) {
        locks.getOrPut(pkg) { Mutex() }.withLock {
            val version = try { pm.getPackageInfo(pkg, 0).longVersionCode } catch (_: Exception) {
                cache.remove(pkg); resources.remove(pkg); return@withLock null
            }
            cache[pkg]?.takeIf { it.versionCode == version }?.let { return@withLock it }
            resources.remove(pkg)
            parse(pkg, version)?.also { cache[pkg] = it }
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun parse(pkg: String, version: Long): LoadedPack? {
        val res = resourcesFor(pkg) ?: return null
        val label = try { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() } catch (_: Exception) { pkg }

        val byComponent = LinkedHashMap<String, String>()
        val byPackage = LinkedHashMap<String, String>()
        val order = LinkedHashSet<String>()
        val componentRe = Regex("""ComponentInfo\{([^/}]+)/([^}]+)\}""")
        val backs = ArrayList<String>(); var mask: String? = null; var upon: String? = null; var scale = 1f
        val calendars = LinkedHashMap<String, String>()
        fun imgs(p: XmlPullParser) = (0 until p.attributeCount).filter { p.getAttributeName(it).startsWith("img") }
            .mapNotNull { p.getAttributeValue(it)?.trim()?.ifEmpty { null } }
        readXml(res, pkg, "appfilter") { p ->
            when (p.name) {
                "iconback" -> backs += imgs(p)
                "iconmask" -> mask = imgs(p).firstOrNull()
                "iconupon" -> upon = imgs(p).firstOrNull()
                "scale" -> p.getAttributeValue(null, "factor")?.toFloatOrNull()?.takeIf { it in 0.2f..1.5f }?.let { scale = it }
                "calendar" -> {
                    val m = componentRe.find(p.getAttributeValue(null, "component") ?: "")
                    val prefix = p.getAttributeValue(null, "prefix")
                    if (m != null && !prefix.isNullOrBlank()) {
                        var cls = m.groupValues[2].trim(); val app = m.groupValues[1].trim()
                        if (cls.startsWith(".")) cls = app + cls
                        calendars[AppScanner.key(app, cls)] = prefix.trim()
                    }
                }
            }
            if (p.name == "item") {
                val comp = p.getAttributeValue(null, "component") ?: return@readXml
                val drawable = p.getAttributeValue(null, "drawable")?.trim()?.ifEmpty { null } ?: return@readXml
                val m = componentRe.find(comp) ?: return@readXml
                val app = m.groupValues[1].trim()
                var cls = m.groupValues[2].trim()
                if (cls.startsWith(".")) cls = app + cls
                byComponent.putIfAbsent(AppScanner.key(app, cls), drawable)
                byPackage.putIfAbsent(app, drawable)
                order += drawable
            }
        }
        readXml(res, pkg, "drawable") { p ->
            if (p.name == "item") p.getAttributeValue(null, "drawable")?.trim()?.ifEmpty { null }?.let { order += it }
        }

        val pack = LoadedPack(pkg, label, findAuthor(res, pkg), version, res, byComponent, byPackage, emptyList())
        val icons = order.filter { pack.drawableId(it) != 0 }
        fun ok(n: String?) = n?.takeIf { pack.drawableId(it) != 0 }
        return LoadedPack(
            pkg, label, pack.author, version, res, byComponent, byPackage, icons,
            backs = backs.mapNotNull(::ok), mask = ok(mask), upon = ok(upon), scale = scale,
            calendars = calendars.filterValues { pack.drawableId(it + "1") != 0 },
        )
    }

    @SuppressLint("DiscouragedApi")
    private fun findAuthor(res: Resources, pkg: String): String {
        val names = listOf(
            "developer_name", "dev_name", "author", "iconpack_author", "app_author", "theme_author",
            "about_dev_name", "dashboard_author", "designer_name", "config_author",
        )
        for (n in names) {
            val id = res.getIdentifier(n, "string", pkg)
            if (id != 0) runCatching { res.getString(id) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() && it.length < 60 }
                ?.let { return it }
        }
        return ""
    }

    /** Reads res/xml/<name>.xml, falling back to assets/<name>.xml. */
    @SuppressLint("DiscouragedApi")
    private fun readXml(res: Resources, pkg: String, name: String, onStart: (XmlPullParser) -> Unit) {
        val id = res.getIdentifier(name, "xml", pkg)
        try {
            if (id != 0) {
                res.getXml(id).useParser { walk(it, onStart) }
                return
            }
            res.assets.open("$name.xml").use { stream ->
                val p = Xml.newPullParser()
                p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                p.setInput(stream, null)
                walk(p, onStart)
            }
        } catch (_: Exception) {
            // Missing or malformed file: keep whatever was read.
        }
    }

    private fun walk(p: XmlPullParser, onStart: (XmlPullParser) -> Unit) {
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) onStart(p)
            e = p.next()
        }
    }
}

private inline fun <T> android.content.res.XmlResourceParser.useParser(block: (android.content.res.XmlResourceParser) -> T): T =
    try { block(this) } finally { close() }

/** Name-similarity suggestions: which icons in a pack probably fit an app. */
object Suggest {
    private val stop = setOf(
        "com", "org", "net", "www", "android", "app", "apps", "mobile", "google", "the", "and", "for",
        "lite", "pro", "free", "inc", "ltd", "activity", "main", "launcher", "client",
    )

    fun tokens(label: String, packageName: String): List<String> =
        (label.lowercase().split(Regex("[^a-z0-9]+")) + packageName.lowercase().split('.', '_'))
            .filter { it.length >= 3 && it !in stop }
            .distinct()

    fun rank(label: String, packageName: String, icons: List<String>, limit: Int = 30): List<String> =
        rankScored(label, packageName, icons, limit).map { it.first }

    /** Score ≥ [CONFIDENT] means a whole word of the app's name matches the icon name. */
    const val CONFIDENT = 7

    fun rankScored(label: String, packageName: String, icons: List<String>, limit: Int = 30): List<Pair<String, Int>> {
        val toks = tokens(label, packageName)
        if (toks.isEmpty()) return emptyList()
        val joined = label.lowercase().replace(Regex("[^a-z0-9]"), "")
        return icons.asSequence().map { name ->
            val parts = name.split('_')
            var score = 0
            if (joined.length >= 3 && name.replace("_", "") == joined) score += 20
            for (t in toks) {
                score += when {
                    name == t -> 12
                    t in parts -> 7
                    t.length >= 4 && name.contains(t) -> 4
                    parts.any { it.length >= 4 && t.contains(it) } -> 2
                    else -> 0
                }
            }
            name to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(limit).toList()
    }
}

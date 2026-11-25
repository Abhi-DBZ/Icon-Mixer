package com.iconforge.app.export

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.iconforge.app.core.IconLoader
import com.iconforge.app.core.IconPacks
import com.iconforge.app.core.IconSrc
import com.iconforge.app.core.ThemeRepository
import com.iconforge.app.data.AppDatabase
import com.iconforge.app.data.MappingEntity
import com.iconforge.app.data.Settings
import com.iconforge.app.data.Source
import com.iconforge.app.data.ThemeEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.security.auth.x500.X500Principal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Everything an export needs, rendered once and shared by the APK and ZIP writers. */
class RenderedTheme(
    val theme: ThemeEntity,
    val packageName: String,
    val icons: List<PackIcon>,
    val mappings: List<PackMapping>,
    val json: JSONObject,
    val collage: PackIcon?,
    val calendars: List<PackCalendar> = emptyList(),
    val live: LiveBundle? = null,
)

/** Turns a theme into an installable icon-pack APK or a ZIP backup, and imports them back. */
class PackExporter(
    private val context: Context,
    private val db: AppDatabase,
    private val packs: IconPacks,
    private val loader: IconLoader,
    private val repo: ThemeRepository,
    private val settings: Settings,
) {
    private val exportDir get() = File(context.cacheDir, "exports").apply { mkdirs() }

    fun packageNameFor(theme: ThemeEntity) = "$PACK_PREFIX${theme.packSuffix}"

    /** An icon pack this app exported that is installed on the phone. */
    data class InstalledPack(
        val packageName: String, val label: String, val versionName: String?,
        /** Signed with this phone's export key, so a re-export can update it in place. */
        val updatable: Boolean,
        /** Theme that exports to this package, if any. */
        val themeId: Long?,
    )

    /** All installed packs made by Icon Mixer. */
    suspend fun installedPacks(): List<InstalledPack> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val mine = runCatching { PackSigner.keyAndCert().second.encoded }.getOrNull()
        val themes = db.themes().all()
        pm.queryIntentActivities(Intent("org.adw.launcher.THEMES"), 0)
            .mapNotNull { it.activityInfo?.packageName }.distinct()
            .filter { it.startsWith(PACK_PREFIX) }
            .mapNotNull { pkg ->
                val info = runCatching {
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                    else pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNATURES)
                }.getOrNull() ?: return@mapNotNull null
                @Suppress("DEPRECATION")
                val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
                val updatable = mine != null && signers?.any { it.toByteArray().contentEquals(mine) } == true
                InstalledPack(
                    pkg, info.applicationInfo?.loadLabel(pm)?.toString() ?: pkg, info.versionName, updatable,
                    themes.firstOrNull { packageNameFor(it) == pkg }?.id,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Keeps one installed pack per name: if this theme's own pack isn't installed but another
     * pack with the same name is (made by this phone's Icon Mixer, not used by another theme),
     * the theme takes over that package so installing updates it instead of adding a second app.
     */
    suspend fun adoptSameNamePack(themeId: Long) {
        val t = db.themes().get(themeId) ?: return
        if (isInstalled(t)) return
        val match = installedPacks().firstOrNull { it.updatable && it.themeId == null && it.label == t.name } ?: return
        repo.setPackSuffix(themeId, match.packageName.removePrefix(PACK_PREFIX))
    }

    /** Opens the system uninstall screen for an exported pack. */
    fun uninstall(packageName: String) {
        context.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object { const val PACK_PREFIX = "com.iconforge.pack." }

    // ------------------------------------------------------------ render

    /**
     * Renders the theme. [forApk] adds calendar icons and, with [live], copies XML icons
     * (Material You) as resources instead of images; ZIP backups always use images.
     */
    suspend fun render(themeId: Long, progress: (Float, String) -> Unit, forApk: Boolean = false, live: Boolean = false): RenderedTheme = withContext(Dispatchers.Default) {
        val theme = db.themes().get(themeId) ?: error("Theme not found")
        val size = settings.exportSize.value
        val shape = com.iconforge.app.core.IconShape.of(theme.iconShape)
        val apps = db.apps().all().associateBy { it.componentKey }
        val maps = db.mappings().forTheme(themeId).sortedBy { apps[it.componentKey]?.label?.lowercase() ?: "~" + it.componentKey }

        val names = HashSet<String>()
        val bySource = HashMap<String, String>()   // icon source key → resource name
        val icons = ArrayList<PackIcon>()
        val outMaps = ArrayList<PackMapping>()
        val jsonMaps = JSONArray()
        val calendars = ArrayList<PackCalendar>()
        val bundle = LiveBundle()
        val liveBySource = HashMap<String, String>()
        val copiers = HashMap<String, LiveCopier?>()
        val sources = ArrayList<SourceRes>()
        val previews = ArrayList<IconSrc>()

        fun copier(pack: String): LiveCopier? = copiers.getOrPut(pack) {
            runCatching {
                val ai = context.packageManager.getApplicationInfo(pack, 0)
                val apks = listOfNotNull(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
                SourceRes(apks.map { File(it) }).also { sources += it }.let { LiveCopier(it, pack) }
            }.getOrNull()
        }

        fun unique(base: String): String {
            var n = base; var i = 2
            while (!names.add(n)) n = "${base}_${i++}"
            return n
        }

        fun addIcon(src: IconSrc, base: String): String? {
            bySource[src.key]?.let { return it }
            // Draw in light and dark mode; keep the dark picture only when it differs
            // (packs with day/night icons), so the launcher can switch with the phone's theme.
            val bmp = loader.renderForExport(src, size, shape, night = false) ?: return null
            val dark = loader.renderForExport(src, size, shape, night = true)?.takeIf { !it.sameAs(bmp) }
            val name = unique(base)
            icons += PackIcon(name, bmp.png(), dark?.png())
            bySource[src.key] = name
            return name
        }

        /** Live copy of a pack's XML icon; null = not XML / not copyable (use an image). */
        fun addLive(m: MappingEntity, base: String): String? {
            val pack = m.iconPack ?: return null; val drawable = m.iconResource ?: return null
            val k = "$pack:$drawable"
            liveBySource[k]?.let { return it }
            val c = copier(pack) ?: return null
            val name = unique(base)
            // With a theme shape, adaptive icons are rewritten already cut to it (still live).
            val ok = c.collect(drawable, name, bundle, shape = ShapePaths.svg(shape.name), shapeAll = settings.shapeAllIcons.value)
            return if (ok) name.also { liveBySource[k] = it } else { names.remove(name); null }
        }

        maps.forEachIndexed { i, m ->
            if (i % 5 == 0) progress(i.toFloat() / maps.size.coerceAtLeast(1) * 0.85f, "Drawing icons ${i + 1}/${maps.size}")
            val app = apps[m.componentKey]
            val src = IconSrc.of(m, app) ?: return@forEachIndexed
            if (m.source == Source.ORIGINAL && app == null) return@forEachIndexed
            val base = when (m.source) {
                Source.PACK -> ApkBuilder.resName(m.iconResource ?: m.packageName)
                Source.CUSTOM -> "custom_" + ApkBuilder.resName(m.packageName)
                else -> "app_" + ApkBuilder.resName(m.packageName)
            }
            // Pack icons only; adjusted ones (size / plate) are pictures.
            val canLive = forApk && live && m.source == Source.PACK && m.scale == 1f && m.bgColor == null
            val res = (if (canLive) addLive(m, base) else null) ?: addIcon(src, base) ?: return@forEachIndexed
            if (previews.size < 4) previews += src
            val cls = m.componentKey.substringAfter('/')
            outMaps += PackMapping(m.packageName, cls, res)
            // Dynamic calendar icon (date on the icon), when the source pack has one for this app.
            if (forApk && m.source == Source.PACK && m.iconPack != null) {
                val pack = packs.load(m.iconPack)
                val prefix = pack?.calendars?.get(m.componentKey)
                if (prefix != null) {
                    val newPrefix = "cal_" + ApkBuilder.resName(m.packageName) + "_"
                    val ok = (1..31).all { d ->
                        val n = newPrefix + d
                        names.add(n) && loader.renderForExport(IconSrc.Pack(m.iconPack, prefix + d), size, shape)
                            ?.let { icons += PackIcon(n, it.png()); true } == true
                    }
                    if (ok) calendars += PackCalendar(m.packageName, cls, newPrefix)
                }
            }
            jsonMaps.put(JSONObject().apply {
                put("component", m.componentKey); put("package", m.packageName); put("source", m.source)
                put("pack", m.iconPack ?: JSONObject.NULL); put("drawable", m.iconResource ?: JSONObject.NULL)
                put("manual", m.isManual); put("icon", res)
                app?.let { put("label", it.label) }
            })
        }

        // Optional: carry the whole base pack, so apps installed later are themed too.
        val base = repo.themePack(themeId)
        if (settings.includeWholePack.value && base != null) {
            val pack = packs.load(base)
            if (pack != null) {
                val done = outMaps.map { "${it.packageName}/${it.activityName}" }.toHashSet()
                val entries = pack.byComponent.entries.filter { it.key !in done }
                entries.forEachIndexed { i, (comp, drawable) ->
                    if (i % 25 == 0) progress(0.85f * (i.toFloat() / entries.size), "Copying ${pack.label} ${i + 1}/${entries.size}")
                    val res = addIcon(IconSrc.Pack(base, drawable), ApkBuilder.resName(drawable)) ?: return@forEachIndexed
                    outMaps += PackMapping(comp.substringBefore('/'), comp.substringAfter('/'), res)
                }
            }
        }

        progress(0.88f, "Packing")
        sources.forEach { it.close() }
        val previewPngs = previews.mapNotNull { loader.renderForExport(it, size, shape)?.png() }
        val collage = previewPngs.takeIf { it.isNotEmpty() }?.let { PackIcon(unique("ic_pack_preview"), collage(it, size)) }
        val json = JSONObject().apply {
            put("format", "iconforge-theme"); put("version", 1)
            put("name", theme.name); put("createdAt", theme.createdAt); put("exportedAt", System.currentTimeMillis())
            put("basePack", theme.basePack ?: JSONObject.NULL); put("iconSize", size); put("iconShape", theme.iconShape)
            put("packageName", packageNameFor(theme)); put("mappings", jsonMaps)
        }
        RenderedTheme(theme, packageNameFor(theme), icons, outMaps, json, collage, calendars, bundle.takeIf { !it.isEmpty })
    }

    private fun Bitmap.png(): ByteArray = ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    private fun collage(icons: List<ByteArray>, size: Int): ByteArray {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val half = size / 2; val pad = size / 16
        icons.forEachIndexed { i, icon ->
            val bmp = android.graphics.BitmapFactory.decodeByteArray(icon, 0, icon.size) ?: return@forEachIndexed
            val x = (i % 2) * half; val y = (i / 2) * half
            c.drawBitmap(bmp, null, Rect(x + pad, y + pad, x + half - pad, y + half - pad), null)
        }
        return out.png()
    }

    // ------------------------------------------------------------ APK

    /** Builds and signs the pack. Returns the APK in the app's cache. */
    suspend fun buildApk(themeId: Long, progress: (Float, String) -> Unit): File {
        adoptSameNamePack(themeId)
        val r = render(themeId, progress, forApk = true, live = settings.liveColours.value)
        return withContext(Dispatchers.IO) {
            progress(0.92f, "Building APK")
            val icons = r.icons + listOfNotNull(r.collage)
            val vc = ((System.currentTimeMillis() / 1000) - 1_700_000_000L).toInt().coerceAtLeast(1)
            val spec = PackSpec(
                packageName = r.packageName,
                label = r.theme.name,
                versionCode = vc,
                versionName = "1.${r.theme.exportCount + 1}",
                icons = icons,
                mappings = r.mappings,
                appIconRes = r.collage?.resName,
                extraFiles = mapOf("assets/iconforge/theme.json" to r.json.toString(2).toByteArray()),
                calendars = r.calendars,
                live = r.live,
            )
            exportDir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            val unsigned = File(exportDir, "unsigned.apk")
            val signed = File(exportDir, fileName(r.theme.name) + ".apk")
            ApkBuilder.buildUnsigned(spec, unsigned)
            progress(0.97f, "Signing")
            val (key, cert) = PackSigner.keyAndCert()
            ApkBuilder.sign(unsigned, signed, key, listOf(cert))
            unsigned.delete()
            repo.markExported(themeId)
            progress(1f, "Done")
            signed
        }
    }

    /**
     * Hands the APK to the system installer. With [silent] (Android 12+) an update of a pack
     * this app installed earlier goes through without the confirm screen.
     */
    fun install(apk: File, silent: Boolean = false, selfUpdate: Boolean = false) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (silent && Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("pack.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java).putExtra(InstallResultReceiver.EXTRA_SILENT, silent)
                .putExtra(InstallResultReceiver.EXTRA_SELF, selfUpdate)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    fun isInstalled(theme: ThemeEntity): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageNameFor(theme), 0) }.isSuccess

    // ------------------------------------------------------------ ZIP

    suspend fun exportZip(themeId: Long, uri: Uri, progress: (Float, String) -> Unit) {
        val r = render(themeId, progress)
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { os ->
                ZipOutputStream(os.buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry("theme.json")); zip.write(r.json.toString(2).toByteArray()); zip.closeEntry()
                    r.icons.forEach { zip.putNextEntry(ZipEntry("icons/${it.resName}.png")); zip.write(it.png); zip.closeEntry() }
                }
            }
            progress(1f, "Done")
        }
    }

    /** ZIP backup in the cache, for sharing straight to another app. */
    suspend fun shareZip(themeId: Long): File {
        val theme = db.themes().get(themeId) ?: error("Theme not found")
        val dir = File(context.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val f = File(dir, fileName(theme.name) + ".zip")
        exportZip(themeId, Uri.fromFile(f)) { _, _ -> }
        return f
    }

    fun fileName(name: String) = name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '_').ifEmpty { "IconTheme" }

    // ------------------------------------------------------------ import

    /**
     * Imports an IconForge ZIP backup or exported APK (theme.json inside), or — best effort —
     * any icon-pack APK/ZIP with an appfilter.xml and PNG icons. Returns the new theme id.
     */
    suspend fun import(uri: Uri): Long = withContext(Dispatchers.IO) {
        val files = HashMap<String, ByteArray>()
        context.contentResolver.openInputStream(uri)!!.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val n = e.name
                    if (!e.isDirectory && (n.endsWith(".json") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith("appfilter.xml"))) {
                        files[n] = zip.readBytes()
                    }
                    e = zip.nextEntry
                }
            }
        }
        val json = (files["theme.json"] ?: files["assets/iconforge/theme.json"])?.let { JSONObject(String(it)) }
        if (json != null) importJson(json, files) else importAppfilter(files)
    }

    private fun iconBytes(files: Map<String, ByteArray>, name: String): ByteArray? =
        files["icons/$name.png"] ?: files.entries.firstOrNull {
            it.key.startsWith("res/") && it.key.substringAfterLast('/').substringBeforeLast('.') == name
        }?.value

    private suspend fun uniqueName(base: String): String {
        val existing = db.themes().all().map { it.name }.toSet()
        if (base !in existing) return base
        var i = 2
        while ("$base ($i)" in existing) i++
        return "$base ($i)"
    }

    private suspend fun importJson(json: JSONObject, files: Map<String, ByteArray>): Long {
        val base = json.optString("basePack").takeIf { it.isNotEmpty() && it != "null" && packs.load(it) != null }
        val id = repo.createTheme(uniqueName(json.optString("name", "Imported theme")), null)
        // Keep updating the pack this theme was exported as, when it's installed here.
        json.optString("packageName").takeIf { it.startsWith(PACK_PREFIX) }?.let { repo.setPackSuffix(id, it.removePrefix(PACK_PREFIX)) }
        if (base != null) repo.setBasePack(id, base)
        json.optString("iconShape").takeIf { it.isNotEmpty() }?.let { repo.setIconShape(id, it) }
        val dir = File(repo.customDir, "t$id").apply { mkdirs() }
        val arr = json.optJSONArray("mappings") ?: JSONArray()
        val out = ArrayList<MappingEntity>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val comp = o.optString("component").takeIf { it.contains('/') } ?: continue
            val pkg = o.optString("package", comp.substringBefore('/'))
            val manual = o.optBoolean("manual", true)
            val source = o.optString("source")
            val pack = o.optString("pack").takeIf { it.isNotEmpty() && it != "null" }
            val drawable = o.optString("drawable").takeIf { it.isNotEmpty() && it != "null" }
            val loaded = pack?.let { packs.load(it) }
            out += when {
                source == Source.PACK && loaded != null && drawable != null && loaded.drawableId(drawable) != 0 ->
                    MappingEntity(id, comp, pkg, Source.PACK, pack, drawable, null, manual)
                source == Source.ORIGINAL -> MappingEntity(id, comp, pkg, Source.ORIGINAL, null, null, null, manual)
                else -> {
                    val icon = o.optString("icon")
                    val bytes = iconBytes(files, icon) ?: continue
                    val f = File(dir, "${ApkBuilder.resName(icon)}.png").apply { if (!exists()) writeBytes(bytes) }
                    MappingEntity(id, comp, pkg, Source.CUSTOM, pack, drawable, f.path, manual)
                }
            }
        }
        db.mappings().put(out)
        repo.refreshStats(id)
        return id
    }

    private suspend fun importAppfilter(files: Map<String, ByteArray>): Long {
        val xml = (files["assets/appfilter.xml"] ?: files["appfilter.xml"])?.let { String(it) }
            ?: error("No theme.json or appfilter.xml in this file")
        val id = repo.createTheme(uniqueName("Imported pack"), null)
        val dir = File(repo.customDir, "t$id").apply { mkdirs() }
        val re = Regex("""component="ComponentInfo\{([^/}]+)/([^}]+)\}"\s+drawable="([^"]+)"""")
        val out = ArrayList<MappingEntity>()
        re.findAll(xml).forEach { m ->
            val (pkg, clsRaw, icon) = m.destructured
            val cls = if (clsRaw.startsWith(".")) pkg + clsRaw else clsRaw
            val bytes = iconBytes(files, icon) ?: return@forEach
            val f = File(dir, "${ApkBuilder.resName(icon)}.png").apply { if (!exists()) writeBytes(bytes) }
            out += MappingEntity(id, "$pkg/$cls", pkg, Source.CUSTOM, null, icon, f.path, isManual = true)
        }
        db.mappings().put(out.distinctBy { it.componentKey })
        repo.refreshStats(id)
        return id
    }
}

/** Key that signs exported packs; lives in the phone's Android Keystore, so re-exports update in place. */
object PackSigner {
    private const val ALIAS = "iconforge_pack_signer"

    fun keyAndCert(): Pair<PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val now = System.currentTimeMillis()
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=Icon Mixer Pack"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(now - 86_400_000L))
                .setCertificateNotAfter(Date(now + 30L * 365 * 86_400_000L))
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }
        return (ks.getKey(ALIAS, null) as PrivateKey) to (ks.getCertificate(ALIAS) as X509Certificate)
    }
}

/** Result of a PackageInstaller session; shows the confirm screen and reports the outcome. */
class InstallResultReceiver : BroadcastReceiver() {
    companion object { const val EXTRA_SILENT = "silent"; const val EXTRA_SELF = "self" }

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Activities can't be started from the background: ask via a notification instead.
                if (com.iconforge.app.AppState.foreground) context.startActivity(confirm)
                else com.iconforge.app.Notifications.packUpdate(context, "Update your icon pack",
                    "New icons are ready. Tap to install the update.", confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                if (intent.getBooleanExtra(EXTRA_SELF, false)) return
                InstallEvents.emit("Icon pack installed. Now pick it in your launcher's icon settings.")
                if (!com.iconforge.app.AppState.foreground && intent.getBooleanExtra(EXTRA_SILENT, false)) {
                    com.iconforge.app.Notifications.packUpdate(context, "Icon pack updated", "New apps now have icons on your home screen.", null)
                }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallEvents.emit("Install cancelled")
            PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallEvents.emit(
                "Couldn't update: an older copy signed with a different key is installed. Uninstall it, then install again.")
            PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallEvents.emit("Install blocked by the phone. Allow Icon Mixer to install apps in Settings.")
            else -> InstallEvents.emit("Install failed (${status}): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"}")
        }
    }
}

object InstallEvents {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events
    fun emit(msg: String) { _events.tryEmit(msg) }
}

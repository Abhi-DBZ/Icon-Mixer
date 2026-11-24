package com.iconforge.app.core

import android.content.Context
import com.iconforge.app.data.AppDatabase
import com.iconforge.app.data.IconPackEntity
import com.iconforge.app.data.InstalledApp
import com.iconforge.app.data.MappingEntity
import com.iconforge.app.data.Settings
import com.iconforge.app.data.Source
import com.iconforge.app.data.ThemeEntity
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class Coverage(val total: Int, val themed: Int) {
    val missing get() = total - themed
    val percent get() = if (total == 0) 0 else (themed * 100f / total).toInt()
    val fraction get() = if (total == 0) 0f else themed.toFloat() / total
}

data class SyncResult(
    val newApps: List<InstalledApp>, val stillMissing: List<InstalledApp>, val removed: Int,
    /** New apps that got an icon in the active theme (reason to update the installed pack). */
    val activeThemedNew: Int = 0,
)

/** All theme / mapping changes go through here so they can be undone. */
class ThemeRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val packs: IconPacks,
    private val scanner: AppScanner,
    private val settings: Settings,
) {
    private class UndoStep(val themeId: Long, val label: String, val before: List<MappingEntity>, val affected: Set<String>)

    private val undoStack = ArrayDeque<UndoStep>()
    private val _undoLabel = MutableStateFlow<String?>(null)
    /** Description of the change Undo would revert, or null. */
    val undoLabel: StateFlow<String?> = _undoLabel
    private val syncLock = Mutex()

    val customDir: File get() = File(context.filesDir, "custom").apply { mkdirs() }

    // ------------------------------------------------------------ themes

    suspend fun createTheme(name: String, basePack: String?): Long {
        val now = System.currentTimeMillis()
        val id = db.themes().insert(
            ThemeEntity(name = name, createdAt = now, updatedAt = now, basePack = basePack, packSuffix = newSuffix()),
        )
        if (basePack != null) autoMatchInternal(id, basePack, onlyMissing = false, overwriteManual = false, keys = null)
        refreshStats(id)
        if (settings.activeTheme.value == 0L || db.themes().get(settings.activeTheme.value) == null) settings.setActiveTheme(id)
        return id
    }

    suspend fun rename(id: Long, name: String) {
        val t = db.themes().get(id) ?: return
        db.themes().update(t.copy(name = name.trim().ifEmpty { t.name }, updatedAt = System.currentTimeMillis()))
    }

    /** Points the theme at an existing pack package (com.iconforge.pack.<suffix>) so exports update it. */
    suspend fun setPackSuffix(id: Long, suffix: String) {
        val t = db.themes().get(id) ?: return
        if (t.packSuffix != suffix && db.themes().all().none { it.id != id && it.packSuffix == suffix }) {
            db.themes().update(t.copy(packSuffix = suffix))
        }
    }

    suspend fun setBasePack(id: Long, pack: String?) {
        val t = db.themes().get(id) ?: return
        db.themes().update(t.copy(basePack = pack, updatedAt = System.currentTimeMillis()))
    }

    suspend fun setIconShape(id: Long, shape: String) {
        val t = db.themes().get(id) ?: return
        if (t.iconShape != shape) db.themes().update(t.copy(iconShape = shape, updatedAt = System.currentTimeMillis()))
    }

    suspend fun duplicate(id: Long): Long? {
        val t = db.themes().get(id) ?: return null
        val now = System.currentTimeMillis()
        val newId = db.themes().insert(
            t.copy(id = 0, name = "${t.name} (copy)", createdAt = now, updatedAt = now, packSuffix = newSuffix(),
                lastExportedAt = 0, exportCount = 0),
        )
        db.mappings().put(db.mappings().forTheme(id).map { it.copy(themeId = newId) })
        refreshStats(newId)
        return newId
    }

    suspend fun delete(id: Long) {
        val mine = db.mappings().forTheme(id).mapNotNull { it.customPath }.toSet()
        db.mappings().deleteTheme(id)
        db.themes().delete(id)
        // Remove custom images no other theme uses.
        val used = db.themes().all().flatMap { t -> db.mappings().forTheme(t.id).mapNotNull { it.customPath } }.toSet()
        (mine - used).forEach { File(it).delete() }
        undoStack.removeAll { it.themeId == id }; publishUndo()
        if (settings.activeTheme.value == id) settings.setActiveTheme(db.themes().all().maxByOrNull { it.updatedAt }?.id ?: 0L)
    }

    suspend fun markExported(id: Long) {
        val t = db.themes().get(id) ?: return
        db.themes().update(t.copy(lastExportedAt = System.currentTimeMillis(), exportCount = t.exportCount + 1))
    }

    private fun newSuffix(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz"
        return "t" + (1..9).map { chars.random() }.joinToString("")
    }

    // ------------------------------------------------------------ mapping edits (undoable)

    private suspend fun change(themeId: Long, keys: Collection<String>?, label: String, block: suspend () -> Unit) {
        val before = if (keys == null) db.mappings().forTheme(themeId) else db.mappings().get(themeId, keys.toList())
        block()
        val after = if (keys == null) db.mappings().forTheme(themeId) else db.mappings().get(themeId, keys.toList())
        if (before.toSet() != after.toSet()) {
            val affected = (keys?.toSet() ?: emptySet()) + before.map { it.componentKey } + after.map { it.componentKey }
            undoStack.addLast(UndoStep(themeId, label, before, affected))
            while (undoStack.size > 50) undoStack.removeFirst()
            publishUndo()
        }
        touch(themeId)
    }

    private fun publishUndo() { _undoLabel.value = undoStack.lastOrNull()?.label }

    suspend fun undo(): String? {
        val step = undoStack.removeLastOrNull() ?: return null
        db.mappings().replace(step.themeId, step.affected.toList(), step.before)
        publishUndo()
        touch(step.themeId)
        return step.label
    }

    /** Manually assign one icon to apps. */
    suspend fun assign(themeId: Long, apps: List<InstalledApp>, source: String, pack: String? = null,
                       drawable: String? = null, customPath: String? = null) {
        val label = if (apps.size == 1) "Icon for ${apps[0].label}" else "Icon for ${apps.size} apps"
        change(themeId, apps.map { it.componentKey }, label) {
            db.mappings().put(apps.map {
                MappingEntity(themeId, it.componentKey, it.packageName, source, pack, drawable, customPath, isManual = true)
            })
            db.apps().clearNew(apps.map { it.componentKey })
        }
    }

    /** Remove the assignment, so the apps show up as missing. */
    suspend fun clear(themeId: Long, keys: List<String>) =
        change(themeId, keys, "Removed ${keys.size} icon(s)") { db.mappings().delete(themeId, keys) }

    /** Back to what the theme's base pack gives (or nothing). */
    suspend fun resetApps(themeId: Long, keys: List<String>) {
        val base = db.themes().get(themeId)?.basePack
        change(themeId, keys, "Reset ${keys.size} app(s)") {
            db.mappings().delete(themeId, keys)
            if (base != null) autoMatchInternal(themeId, base, onlyMissing = true, overwriteManual = false, keys = keys)
        }
    }

    suspend fun resetTheme(themeId: Long) {
        val base = db.themes().get(themeId)?.basePack
        change(themeId, null, "Reset theme") {
            db.mappings().deleteTheme(themeId)
            if (base != null) autoMatchInternal(themeId, base, onlyMissing = false, overwriteManual = false, keys = null)
        }
    }

    /**
     * Assigns a pack's matching icons.
     * onlyMissing: fill apps without an icon. Otherwise replace auto-assigned icons too
     * (and manual ones when [overwriteManual], used for explicit bulk edits).
     * Returns how many apps got an icon.
     */
    suspend fun autoMatch(themeId: Long, pack: String, onlyMissing: Boolean, overwriteManual: Boolean = false,
                          keys: List<String>? = null): Int {
        var n = 0
        val label = (packs.cached(pack)?.label ?: "pack").let { if (onlyMissing) "Filled from $it" else "Applied $it" }
        change(themeId, keys, label) { n = autoMatchInternal(themeId, pack, onlyMissing, overwriteManual, keys) }
        return n
    }

    private suspend fun autoMatchInternal(themeId: Long, packPkg: String, onlyMissing: Boolean,
                                          overwriteManual: Boolean, keys: Collection<String>?): Int {
        val pack = packs.load(packPkg) ?: return 0
        val apps = db.apps().all().let { all -> if (keys == null) all else keys.toSet().let { k -> all.filter { it.componentKey in k } } }
        val existing = db.mappings().forTheme(themeId).associateBy { it.componentKey }
        val manual = keys != null && overwriteManual
        val out = ArrayList<MappingEntity>()
        for (app in apps) {
            val cur = existing[app.componentKey]
            if (cur != null && (onlyMissing || (cur.isManual && !overwriteManual))) continue
            val drawable = pack.match(app.packageName, app.activityName) ?: continue
            out += MappingEntity(themeId, app.componentKey, app.packageName, Source.PACK, packPkg, drawable, null, isManual = manual)
        }
        db.mappings().put(out)
        return out.size
    }

    data class FillResult(val matched: Int, val guessed: Int, val generated: Int) {
        val total get() = matched + guessed + generated
    }

    /** The pack a theme is built on: its base pack, else the pack most of its icons come from. */
    suspend fun themePack(themeId: Long): String? {
        val t = db.themes().get(themeId) ?: return null
        return t.basePack ?: db.mappings().forTheme(themeId).mapNotNull { it.iconPack }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }

    /**
     * One tap towards 100%, keeping the theme's look: by default only the theme's own pack is
     * used. For apps without an icon, in this order
     *  1. icons the pack(s) made for exactly that app,
     *  2. best name guesses (whole-word matches) — marked "to review",
     *  3. the app's own icon redrawn in the theme pack's style — marked "to review".
     * [otherPacks] lets steps 1–2 also use the other installed packs (mixes styles).
     */
    suspend fun smartFill(themeId: Long, guesses: Boolean, generate: Boolean, otherPacks: Boolean): FillResult {
        var r = FillResult(0, 0, 0)
        val own = themePack(themeId)
        change(themeId, null, "Auto-fill") {
            val others = if (otherPacks) db.packs().all().sortedByDescending { it.mappedComponents }.map { it.packageName } else emptyList()
            val order = (listOfNotNull(own) + others).distinct()
            suspend fun missing(existing: Set<String>) = db.apps().all().filter { it.componentKey !in existing }
            var matched = 0; var guessed = 0; var generated = 0
            for (p in order) {
                matched += autoMatchInternal(themeId, p, onlyMissing = true, overwriteManual = false, keys = null)
            }
            if (guesses) {
                val out = ArrayList<MappingEntity>()
                val have = db.mappings().forTheme(themeId).map { it.componentKey }.toSet()
                val loaded = order.mapNotNull { packs.load(it) }
                for (app in missing(have)) {
                    val best = loaded.asSequence().mapNotNull { p ->
                        Suggest.rankScored(app.label, app.packageName, p.icons, 1).firstOrNull()?.let { p to it }
                    }.filter { it.second.second >= Suggest.CONFIDENT }.maxByOrNull { it.second.second } ?: continue
                    out += MappingEntity(themeId, app.componentKey, app.packageName, Source.PACK, best.first.packageName,
                        best.second.first, null, isManual = false, isGuess = true)
                }
                db.mappings().put(out); guessed = out.size
            }
            if (generate) {
                // Always the theme's own pack (no style kit → the app icon in the theme's shape).
                val stylePack = own
                if (stylePack != null) {
                    val have = db.mappings().forTheme(themeId).map { it.componentKey }.toSet()
                    val out = missing(have).map {
                        MappingEntity(themeId, it.componentKey, it.packageName, Source.GENERATED, stylePack, null, null,
                            isManual = false, isGuess = true)
                    }
                    db.mappings().put(out); generated = out.size
                }
            }
            r = FillResult(matched, guessed, generated)
        }
        return r
    }

    /** Redraw the apps' own icons in a pack's style. */
    suspend fun generate(themeId: Long, apps: List<InstalledApp>, stylePack: String) =
        change(themeId, apps.map { it.componentKey }, "Generated ${apps.size} icon(s)") {
            db.mappings().put(apps.map {
                MappingEntity(themeId, it.componentKey, it.packageName, Source.GENERATED, stylePack, null, null, isManual = true)
            })
        }

    /** Keep auto-filled icons: they leave the "to review" list. */
    suspend fun accept(themeId: Long, keys: List<String>) = change(themeId, keys, "Accepted ${keys.size} icon(s)") {
        db.mappings().put(db.mappings().get(themeId, keys).map { it.copy(isGuess = false) })
    }

    /** Icons Auto-fill added and nobody kept or chose (guesses + generated). */
    suspend fun autoFilledKeys(themeId: Long): List<String> = db.mappings().forTheme(themeId)
        .filter { it.isGuess || (it.source == Source.GENERATED && !it.isManual) }.map { it.componentKey }

    /** Takes back Auto-fill: those apps become "missing" again. Undoable. */
    suspend fun removeAutoFilled(themeId: Long): Int {
        val keys = autoFilledKeys(themeId)
        if (keys.isNotEmpty()) change(themeId, keys, "Removed ${keys.size} auto-filled icon(s)") { db.mappings().delete(themeId, keys) }
        return keys.size
    }

    suspend fun acceptAll(themeId: Long) {
        val keys = db.mappings().forTheme(themeId).filter { it.isGuess }.map { it.componentKey }
        if (keys.isNotEmpty()) accept(themeId, keys)
    }

    /** Size / background plate for one app's icon. */
    suspend fun setAdjust(themeId: Long, key: String, scale: Float, bgColor: Int?) = change(themeId, listOf(key), "Adjusted icon") {
        db.mappings().get(themeId, listOf(key)).firstOrNull()?.let {
            db.mappings().put(listOf(it.copy(scale = scale, bgColor = bgColor, isGuess = false, lastUpdated = System.currentTimeMillis())))
        }
    }

    /** How many installed apps a pack would theme (for previews). */
    suspend fun packCoverage(packPkg: String): Coverage {
        val pack = packs.load(packPkg) ?: return Coverage(0, 0)
        val apps = db.apps().all()
        return Coverage(apps.size, apps.count { pack.match(it.packageName, it.activityName) != null })
    }

    // ------------------------------------------------------------ stats

    suspend fun coverage(themeId: Long): Coverage {
        val apps = db.apps().all()
        val mapped = db.mappings().forTheme(themeId).map { it.componentKey }.toSet()
        return Coverage(apps.size, apps.count { it.componentKey in mapped })
    }

    suspend fun refreshStats(themeId: Long) {
        val t = db.themes().get(themeId) ?: return
        val c = coverage(themeId)
        db.themes().update(t.copy(totalApps = c.total, themedApps = c.themed, coverage = c.fraction))
    }

    private suspend fun touch(themeId: Long) {
        val t = db.themes().get(themeId) ?: return
        val c = coverage(themeId)
        db.themes().update(t.copy(updatedAt = System.currentTimeMillis(), totalApps = c.total, themedApps = c.themed, coverage = c.fraction))
    }

    // ------------------------------------------------------------ device sync

    /**
     * Re-scans launchable apps. New apps are auto-filled in every theme from the theme's
     * base pack, then from the other packs that theme already uses; whatever is still
     * unthemed in the active theme is returned so the caller can notify.
     */
    suspend fun syncApps(): SyncResult = syncLock.withLock {
        val current = scanner.scan()
        val old = db.apps().all().associateBy { it.componentKey }
        val firstRun = old.isEmpty()
        val merged = current.map { app ->
            old[app.componentKey]?.let { app.copy(firstSeen = it.firstSeen, isNew = it.isNew) } ?: app.copy(isNew = !firstRun)
        }
        val added = merged.filter { it.componentKey !in old }
        val removed = old.keys - current.map { it.componentKey }.toSet()
        db.apps().upsert(merged)
        if (removed.isNotEmpty()) db.apps().delete(removed.toList())

        val stillMissing = ArrayList<InstalledApp>()
        var activeThemedNew = 0
        for (theme in db.themes().all()) {
            if (added.isNotEmpty() && !firstRun) {
                val keys = added.map { it.componentKey }
                // Only the theme's own pack, so new apps never bring in another pack's style.
                themePack(theme.id)?.let { p ->
                    autoMatchInternal(theme.id, p, onlyMissing = true, overwriteManual = false, keys = keys)
                }
                if (theme.id == settings.activeTheme.value) {
                    val mapped = db.mappings().get(theme.id, keys).map { it.componentKey }.toSet()
                    stillMissing += added.filter { it.componentKey !in mapped }
                    activeThemedNew = mapped.size
                }
            }
            refreshStats(theme.id)
        }
        SyncResult(added, stillMissing, removed.size, activeThemedNew)
    }

    /** Detects installed icon packs and stores their summary. */
    suspend fun syncPacks(): List<IconPackEntity> {
        val found = packs.detect()
        val known = db.packs().all().associateBy { it.packageName }
        val out = found.map { info ->
            known[info.packageName]?.takeIf { it.versionCode == info.versionCode && it.totalIcons > 0 }
                ?: packs.load(info.packageName)?.let {
                    IconPackEntity(it.packageName, it.label, it.author, it.icons.size, it.byComponent.size,
                        it.versionCode, System.currentTimeMillis())
                }
                ?: IconPackEntity(info.packageName, info.label, "", 0, 0, info.versionCode, System.currentTimeMillis())
        }
        db.packs().upsert(out)
        db.packs().deleteOthers(out.map { it.packageName })
        return out
    }
}

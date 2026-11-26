package com.iconforge.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iconforge.app.IconForgeApp
import com.iconforge.app.core.Coverage
import com.iconforge.app.core.LoadedPack
import com.iconforge.app.core.Suggest
import com.iconforge.app.data.IconPackEntity
import com.iconforge.app.data.InstalledApp
import com.iconforge.app.data.MappingEntity
import com.iconforge.app.data.Source
import com.iconforge.app.data.ThemeEntity
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun <T> kotlinx.coroutines.flow.Flow<T>.state(vm: AndroidViewModel, initial: T): StateFlow<T> =
    stateIn(vm.viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

data class AppRow(val app: InstalledApp, val mapping: MappingEntity?)

/** App-wide state: themes, apps, packs, and theme-level actions. Activity-scoped. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    val a = application as IconForgeApp
    private val repo = a.repo

    val themes = a.db.themes().observe().state(this, emptyList())
    val apps = a.db.apps().observe().state(this, emptyList())
    val packs = a.db.packs().observe().state(this, emptyList())
    val undoLabel = repo.undoLabel
    val undoable = repo.undoable

    val activeTheme: StateFlow<ThemeEntity?> = combine(themes, a.settings.activeTheme) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.state(this, null)

    val activeMappings: StateFlow<Map<String, MappingEntity>> = activeTheme
        .flatMapLatest { t -> if (t == null) flowOf(emptyList()) else a.db.mappings().observe(t.id) }
        .map { list -> list.associateBy { it.componentKey } }
        .state(this, emptyMap())

    val activeCoverage: StateFlow<Coverage> = combine(apps, activeMappings) { apps, maps ->
        Coverage(apps.size, apps.count { it.componentKey in maps })
    }.state(this, Coverage(0, 0))

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy
    /** Snackbar message, optionally with one action (e.g. Undo). */
    data class UiMsg(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

    private val _messages = MutableSharedFlow<UiMsg>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMsg> = _messages
    private val _scanned = MutableStateFlow(false)
    val scanned: StateFlow<Boolean> = _scanned

    init { refresh(first = true) }

    // ------------------------------------------------------------ app updates (GitHub releases)

    sealed interface Update {
        data object Idle : Update
        data object Checking : Update
        data object UpToDate : Update
        data class Available(val release: com.iconforge.app.core.Updater.Release) : Update
        data class Downloading(val progress: Float) : Update
        data class Failed(val message: String) : Update
    }

    private val _update = MutableStateFlow<Update>(Update.Idle)
    val update: StateFlow<Update> = _update

    // Must come after _update: Kotlin runs initialisers top to bottom, and the check
    // starts immediately (it crashed 1.10 on first launch when this ran earlier).
    init { checkOnOpen() }

    private val appVersion: String get() =
        runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrNull() ?: "0"

    /**
     * On launch and whenever the app comes back to the screen: look for a new release, at
     * most every 30 minutes ([force]: now, e.g. from the update notification). A found update
     * shows as a banner on Home until it's installed.
     */
    fun checkOnOpen(force: Boolean = false) {
        if (!force && !a.settings.autoUpdateCheck.value) return
        if (_update.value is Update.Checking || _update.value is Update.Downloading) return
        if (force || System.currentTimeMillis() - a.settings.lastUpdateCheck > 30 * 60_000L) checkForUpdate(manual = false)
    }

    fun checkForUpdate(manual: Boolean) = viewModelScope.launch {
        val before = _update.value
        // A background check keeps an "available" banner on screen instead of flickering.
        if (manual || before !is Update.Available) _update.value = Update.Checking
        _update.value = try {
            val r = com.iconforge.app.core.Updater.latest()
            a.settings.lastUpdateCheck = System.currentTimeMillis()
            if (r != null && com.iconforge.app.core.Updater.isNewer(r.version, appVersion)) Update.Available(r)
            else Update.UpToDate
        } catch (e: Exception) {
            if (manual) Update.Failed("Couldn't reach GitHub: ${e.message ?: "no connection"}")
            else before.takeIf { it is Update.Available } ?: Update.Idle
        }
    }

    /** Downloads the release APK and hands it to the installer (Android asks to confirm). */
    fun installUpdate(r: com.iconforge.app.core.Updater.Release) = viewModelScope.launch {
        if (!a.packageManager.canRequestPackageInstalls()) {
            a.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${a.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            say("Allow Icon Mixer to install apps, then tap Update again")
            return@launch
        }
        try {
            _update.value = Update.Downloading(0f)
            val apk = com.iconforge.app.core.Updater.download(r, java.io.File(a.cacheDir, "update")) { p -> _update.value = Update.Downloading(p) }
            _update.value = Update.Available(r)
            a.exporter.install(apk, selfUpdate = true)
        } catch (e: Exception) {
            _update.value = Update.Failed("Download failed: ${e.message ?: "no connection"}")
        }
    }

    fun say(msg: String) { _messages.tryEmit(UiMsg(msg)) }
    fun say(msg: String, action: String, onAction: () -> Unit) { _messages.tryEmit(UiMsg(msg, action, onAction)) }

    private fun work(label: String?, block: suspend () -> Unit) = viewModelScope.launch {
        if (label != null) _busy.value = label
        try { block() } catch (e: Exception) { say(e.message ?: e.javaClass.simpleName) } finally { if (label != null) _busy.value = null }
    }

    fun refresh(first: Boolean = false) = work(if (first) "Scanning apps and icon packs…" else "Refreshing…") {
        withContext(Dispatchers.IO) {
            val packs = repo.syncPacks()
            val r = repo.syncApps()
            if (!first) say("${apps.value.size} apps · ${packs.size} icon packs" + if (r.newApps.isNotEmpty()) " · ${r.newApps.size} new" else "")
        }
        _scanned.value = true
    }

    /** On resume: pick up installs/uninstalls silently. */
    fun refreshQuietly() = viewModelScope.launch(Dispatchers.IO) {
        runCatching { repo.syncPacks(); repo.syncApps() }
    }

    /** System uninstall screen for any app (icon packs); refreshQuietly picks up the change on return. */
    fun uninstall(pkg: String) = a.exporter.uninstall(pkg)

    fun setActive(id: Long) = a.settings.setActiveTheme(id)

    fun createTheme(name: String, pack: String?, onCreated: (Long) -> Unit = {}) = work("Matching icons…") {
        val id = repo.createTheme(name.trim().ifEmpty { "My theme" }, pack)
        a.settings.setActiveTheme(id)
        val c = repo.coverage(id)
        say("Theme created: ${c.themed} of ${c.total} apps themed (${c.percent}%) · ${c.missing} missing")
        onCreated(id)
    }

    fun rename(id: Long, name: String) = work(null) { repo.rename(id, name) }
    fun duplicate(id: Long) = work(null) { repo.duplicate(id)?.let { say("Theme duplicated") } }
    fun delete(id: Long) = work(null) {
        val gone = repo.delete(id) ?: return@work
        // Images are kept a little while, so Undo brings the theme back complete.
        val purge = viewModelScope.launch { kotlinx.coroutines.delay(30_000); repo.purgeImages(gone) }
        say("\"${gone.theme.name}\" deleted", "Undo") {
            purge.cancel()
            viewModelScope.launch { repo.restore(gone) }
        }
    }
    fun setIconShape(id: Long, shape: String) = work(null) { repo.setIconShape(id, shape) }
    fun setBasePack(id: Long, pack: String?) = work(null) { repo.setBasePack(id, pack) }

    fun fillMissing(themeId: Long, pack: String) = work("Matching icons…") {
        val n = repo.autoMatch(themeId, pack, onlyMissing = true)
        say(if (n == 0) "No extra matches in that pack" else "$n more apps themed")
    }

    fun applyPack(themeId: Long, pack: String) = work("Matching icons…") {
        val n = repo.autoMatch(themeId, pack, onlyMissing = false)
        repo.setBasePack(themeId, pack)
        val c = repo.coverage(themeId)
        say("$n icons applied · coverage ${c.percent}%")
    }

    /** One tap towards 100% (see ThemeRepository.smartFill). */
    fun smartFill(themeId: Long, guesses: Boolean, generate: Boolean, otherPacks: Boolean, onDone: (Int) -> Unit = {}) = work("Filling icons…") {
        val r = repo.smartFill(themeId, guesses, generate, otherPacks)
        val c = repo.coverage(themeId)
        if (r.total == 0) say("Nothing more to fill")
        else say("${r.matched} matched · ${r.guessed} guessed · ${r.generated} generated — coverage ${c.percent}%", "Undo") { undo() }
        onDone(r.guessed + r.generated)
    }

    /** Lasting "go back": removes everything Auto-fill added that wasn't kept or changed. */
    fun removeAutoFilled(themeId: Long) = work(null) {
        val n = repo.removeAutoFilled(themeId)
        if (n == 0) say("No auto-filled icons to remove") else say("Removed $n auto-filled icons", "Undo") { undo() }
    }

    fun acceptAll(themeId: Long) = work(null) { repo.acceptAll(themeId); say("All auto-filled icons kept") }

    /** Rebuild the installed pack now (silent update where Android allows it). */
    fun updatePackNow(themeId: Long) {
        com.iconforge.app.PackUpdateWorker.enqueue(a, themeId)
        say("Updating your icon pack in the background…")
    }

    /** ZIP backup ready to hand to the share sheet. */
    fun shareTheme(themeId: Long, onReady: (File) -> Unit) = work("Preparing to share…") {
        onReady(a.exporter.shareZip(themeId))
    }

    fun isPackInstalled(t: ThemeEntity) = a.exporter.isInstalled(t)

    fun resetTheme(themeId: Long) = work("Resetting…") { repo.resetTheme(themeId); say("Theme reset to base pack") }

    fun undo() = work(null) { repo.undo()?.let { say("Undone: $it") } }

    fun import(uri: Uri, onDone: (Long) -> Unit = {}) = work("Importing…") {
        val id = a.exporter.import(uri)
        a.settings.setActiveTheme(id)
        val c = repo.coverage(id)
        say("Theme imported · ${c.themed}/${c.total} of your apps covered")
        onDone(id)
    }

    fun markAllSeen() = work(null) { a.db.apps().clearAllNew() }
}

/** Theme editor: filtering, search and multi-select over the theme's apps. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModel(application: Application, val themeId: Long) : AndroidViewModel(application) {
    private val a = application as IconForgeApp

    enum class Filter(val label: String) {
        ALL("All"), MISSING("Missing"), REVIEW("To review"), AUTO("Auto"), MANUAL("Manual"), NEW("New"), SYSTEM("System")
    }

    val filter = MutableStateFlow(Filter.ALL)
    val query = MutableStateFlow("")
    val selection = MutableStateFlow<Set<String>>(emptySet())

    val theme = a.db.themes().observe(themeId).state(this, null)
    private val maps = a.db.mappings().observe(themeId).map { l -> l.associateBy { it.componentKey } }
    private val all = combine(a.db.apps().observe(), maps) { apps, m -> apps.map { AppRow(it, m[it.componentKey]) } }

    val counts: StateFlow<Map<Filter, Int>> = all.map { rows -> Filter.entries.associateWith { f -> rows.count { matches(it, f) } } }
        .state(this, emptyMap())

    val rows: StateFlow<List<AppRow>> = combine(all, filter, query) { rows, f, q ->
        val qq = q.trim().lowercase()
        rows.filter { matches(it, f) && (qq.isEmpty() || it.app.label.lowercase().contains(qq) || it.app.packageName.contains(qq)) }
    }.state(this, emptyList())

    private fun matches(r: AppRow, f: Filter) = when (f) {
        Filter.ALL -> true
        Filter.MISSING -> r.mapping == null
        Filter.REVIEW -> r.mapping?.isGuess == true
        Filter.AUTO -> r.mapping != null && !r.mapping.isManual
        Filter.MANUAL -> r.mapping?.isManual == true
        Filter.NEW -> r.app.isNew
        Filter.SYSTEM -> r.app.isSystem
    }

    fun toggle(key: String) { selection.value = selection.value.let { if (key in it) it - key else it + key } }
    fun selectAllVisible() { selection.value = rows.value.map { it.app.componentKey }.toSet() }
    fun clearSelection() { selection.value = emptySet() }

    private fun selected(): List<InstalledApp> = rows.value.map { it.app }.filter { it.componentKey in selection.value }

    fun keepOriginal() = viewModelScope.launch { a.repo.assign(themeId, selected(), Source.ORIGINAL); clearSelection() }
    fun accept() = viewModelScope.launch { a.repo.accept(themeId, selection.value.toList()); clearSelection() }
    fun generate(stylePack: String) = viewModelScope.launch { a.repo.generate(themeId, selected(), stylePack); clearSelection() }
    fun clearIcons() = viewModelScope.launch { a.repo.clear(themeId, selection.value.toList()); clearSelection() }
    fun reset() = viewModelScope.launch { a.repo.resetApps(themeId, selection.value.toList()); clearSelection() }
    fun fromPack(pack: String, onResult: (Int) -> Unit) = viewModelScope.launch {
        val n = a.repo.autoMatch(themeId, pack, onlyMissing = false, overwriteManual = true, keys = selection.value.toList())
        clearSelection(); onResult(n)
    }
}

/** Icon picker for one or more apps. */
@OptIn(ExperimentalCoroutinesApi::class)
class PickerViewModel(application: Application, val themeId: Long, val keys: List<String>) : AndroidViewModel(application) {
    private val a = application as IconForgeApp

    data class Match(val pack: IconPackEntity, val drawable: String)

    val targets: StateFlow<List<InstalledApp>> = a.db.apps().observe()
        .map { list -> list.filter { it.componentKey in keys }.sortedBy { keys.indexOf(it.componentKey) } }
        .state(this, emptyList())
    val mapping: StateFlow<MappingEntity?> = a.db.mappings().observe(themeId)
        .map { l -> l.firstOrNull { it.componentKey == keys.firstOrNull() } }.state(this, null)
    val packs = a.db.packs().observe().state(this, emptyList())
    val theme = a.db.themes().observe(themeId).state(this, null)

    val selectedPack = MutableStateFlow<String?>(null)
    val query = MutableStateFlow("")
    /** Pack category filter (null = all). */
    val category = MutableStateFlow<String?>(null)

    val loaded: StateFlow<LoadedPack?> = selectedPack.mapLatest { p -> p?.let { a.packs.load(it) } }.state(this, null)

    /** Icons of the selected pack: category → search → A–Z by readable name. */
    val icons: StateFlow<List<String>> = combine(loaded, query, category) { p, q, cat ->
        val base = p?.let { pk -> cat?.let { pk.categories[it] } ?: pk.icons } ?: emptyList()
        val qq = com.iconforge.app.core.IconNames.squash(q)
        val hits = if (qq.isEmpty()) base else base.filter {
            com.iconforge.app.core.IconNames.squash(it).contains(qq) || com.iconforge.app.core.IconNames.squash(com.iconforge.app.core.IconNames.pretty(it)).contains(qq)
        }
        hits.sortedBy { com.iconforge.app.core.IconNames.pretty(it).lowercase() }
    }.state(this, emptyList())

    /** Name-based guesses in the selected pack. */
    val suggestions: StateFlow<List<String>> = combine(loaded, targets) { p, t ->
        val app = t.firstOrNull() ?: return@combine emptyList()
        if (p == null) emptyList() else withContext(Dispatchers.Default) { Suggest.rank(app.label, app.packageName, p.icons) }
    }.state(this, emptyList())

    /** Icons other installed packs map to this exact app. */
    val exactMatches: StateFlow<List<Match>> = combine(packs, targets) { ps, t ->
        val app = t.firstOrNull() ?: return@combine emptyList()
        ps.mapNotNull { pe -> a.packs.load(pe.packageName)?.match(app.packageName, app.activityName)?.let { Match(pe, it) } }
    }.state(this, emptyList())

    /** A few of the theme's existing icons, to preview the new one next to. */
    val neighbours: StateFlow<List<MappingEntity>> = a.db.mappings().observe(themeId)
        .map { l -> l.filter { it.source == Source.PACK && it.componentKey !in keys }.shuffled(java.util.Random(themeId)).take(3) }
        .state(this, emptyList())

    init {
        viewModelScope.launch {
            val ps = a.db.packs().all()
            // Open on the theme's own pack (the one its icons come from), not the first alphabetically.
            val own = a.repo.themePack(themeId)
            selectedPack.value = own?.takeIf { b -> ps.any { it.packageName == b } } ?: ps.firstOrNull()?.packageName
        }
    }

    fun apply(pack: String, drawable: String, done: () -> Unit) = viewModelScope.launch {
        a.repo.assign(themeId, targets.value, Source.PACK, pack, drawable); done()
    }

    fun keepOriginal(done: () -> Unit) = viewModelScope.launch { a.repo.assign(themeId, targets.value, Source.ORIGINAL); done() }

    /** "Generate" always uses the theme's own pack, so the theme keeps one look. */
    suspend fun stylePack(): String? = a.repo.themePack(themeId)

    fun generate(done: () -> Unit) = viewModelScope.launch {
        val p = stylePack() ?: return@launch
        a.repo.generate(themeId, targets.value, p); done()
    }

    fun setAdjust(scale: Float, bg: Int?) = viewModelScope.launch {
        keys.forEach { a.repo.setAdjust(themeId, it, scale, bg) }
    }

    fun clear(done: () -> Unit) = viewModelScope.launch { a.repo.clear(themeId, keys); done() }

    fun useImage(uri: Uri, done: (Boolean) -> Unit) = viewModelScope.launch {
        val path = withContext(Dispatchers.IO) {
            runCatching {
                val src = ImageDecoder.createSource(a.contentResolver, uri)
                val bmp = ImageDecoder.decodeBitmap(src) { d, info, _ ->
                    val s = minOf(info.size.width, info.size.height)
                    if (s > 512) d.setTargetSampleSize(s / 512)
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                // centre-crop to a square, 256 px
                val side = minOf(bmp.width, bmp.height)
                val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
                val scaled = Bitmap.createScaledBitmap(sq, 256, 256, true)
                val dir = File(a.repo.customDir, "t$themeId").apply { mkdirs() }
                val f = File(dir, "img_${System.currentTimeMillis()}.png")
                FileOutputStream(f).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
                f.path
            }.getOrNull()
        }
        if (path != null) a.repo.assign(themeId, targets.value, Source.CUSTOM, customPath = path)
        done(path != null)
    }
}

/** Export screen: build/install/save the APK, or write a ZIP. */
class ExportViewModel(application: Application, val themeId: Long) : AndroidViewModel(application) {
    private val a = application as IconForgeApp

    sealed interface State {
        data object Idle : State
        data class Working(val progress: Float, val text: String) : State
        data class ApkReady(val file: File) : State
        data class Done(val text: String) : State
        data class Failed(val text: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)
    val theme = a.db.themes().observe(themeId).state(this, null)
    val settings = a.settings

    private fun progress(p: Float, t: String) { state.value = State.Working(p, t) }

    fun buildApk() = viewModelScope.launch {
        progress(0f, "Starting")
        state.value = try { State.ApkReady(a.exporter.buildApk(themeId, ::progress)) }
        catch (e: Exception) { State.Failed("Export failed: ${e.message ?: e.javaClass.simpleName}") }
    }

    fun exportZip(uri: Uri) = viewModelScope.launch {
        progress(0f, "Starting")
        state.value = try { a.exporter.exportZip(themeId, uri, ::progress); State.Done("ZIP backup saved") }
        catch (e: Exception) { State.Failed("Export failed: ${e.message ?: e.javaClass.simpleName}") }
    }

    fun saveApk(file: File, uri: Uri) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching { a.contentResolver.openOutputStream(uri, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } } }.isSuccess
        }
        a.repo.markExported(themeId)
        state.value = if (ok) State.Done("APK saved. Share it or install it from your file manager.") else State.Failed("Couldn't save the file")
    }

    fun install(file: File) {
        runCatching { a.exporter.install(file) }.onFailure { state.value = State.Failed("Install failed: ${it.message}") }
    }

    fun setIconShape(shape: String) = viewModelScope.launch { a.repo.setIconShape(themeId, shape) }
    fun setIconScale(scale: Float) = viewModelScope.launch { a.repo.setIconScale(themeId, scale) }
    fun isInstalled(t: ThemeEntity) = a.exporter.isInstalled(t)

    /** Icon Mixer packs installed on the phone (refreshed when the screen resumes). */
    val installed = MutableStateFlow<List<com.iconforge.app.export.PackExporter.InstalledPack>>(emptyList())
    fun refreshInstalled() = viewModelScope.launch { installed.value = a.exporter.installedPacks() }
    fun uninstall(pkg: String) = a.exporter.uninstall(pkg)
    fun packageName(t: ThemeEntity) = a.exporter.packageNameFor(t)
    fun fileName(t: ThemeEntity) = a.exporter.fileName(t.name)
}

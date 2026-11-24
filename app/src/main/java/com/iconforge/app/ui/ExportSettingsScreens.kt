package com.iconforge.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.Check
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.iconforge.app.app
import com.iconforge.app.core.IconShape
import com.iconforge.app.data.Settings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExportScreen(nav: NavHostController, themeId: Long) {
    val ctx = LocalContext.current
    val vm: ExportViewModel = viewModel(key = "export-$themeId") { ExportViewModel(ctx.app, themeId) }
    val theme by vm.theme.collectAsState()
    val state by vm.state.collectAsState()
    val size by vm.settings.exportSize.collectAsState()
    val whole by vm.settings.includeWholePack.collectAsState()
    val t = theme ?: return
    val ready = (state as? ExportViewModel.State.ApkReady)?.file
    val installed by vm.installed.collectAsState()
    val themes by ctx.app.db.themes().observe().collectAsState(initial = emptyList())
    // Packs can be installed/removed outside the app: refresh on resume and after each step.
    androidx.lifecycle.compose.LifecycleResumeEffect(state) { vm.refreshInstalled(); onPauseOrDispose { } }

    val saveApk = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
        if (uri != null && ready != null) vm.saveApk(ready, uri)
    }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportZip(uri)
    }
    val working = state is ExportViewModel.State.Working

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                title = { Text("Export ${t.name}") },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val isInstalled = vm.isInstalled(t)
            val mineInstalled = installed.firstOrNull { it.themeId == t.id }
            Text(
                "${t.themedApps}/${t.totalApps} apps have icons · ${(t.coverage * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- APK
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Android, null); Gap(w = 8.dp)
                        Text("Icon pack", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(
                            if (isInstalled) "Installed" + (mineInstalled?.versionName?.let { " · v$it" } ?: "") else "Not installed",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (isInstalled) "Building and installing again updates it in place."
                        else "Install it, then pick “${t.name}” in your launcher's icon settings.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = vm::buildApk, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                        Text(if (ready != null) "Build again" else "Build icon pack")
                    }
                    if (ready != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = {
                                if (!ctx.packageManager.canRequestPackageInstalls()) {
                                    ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:${ctx.packageName}")))
                                    // the user allows it, comes back and taps Install again
                                } else vm.install(ready)
                            }, modifier = Modifier.weight(1f)) { Text("Install") }
                            OutlinedButton(onClick = { saveApk.launch(vm.fileName(t) + ".apk") }) { Text("Save") }
                            OutlinedButton(onClick = {
                                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", ready)
                                val send = Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive")
                                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                ctx.startActivity(Intent.createChooser(send, "Share icon pack"))
                            }) { Text("Share") }
                        }
                        Text("${ready.length() / 1024} KB", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when (val s = state) {
                        is ExportViewModel.State.Working -> Column {
                            Text(s.text, style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        }
                        is ExportViewModel.State.Done -> Text(s.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        is ExportViewModel.State.Failed -> Text(s.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        else -> {}
                    }
                }
            }

            // ---- installed packs (folded; opens by itself when a same-name copy needs attention)
            if (installed.isNotEmpty()) {
                val problems = installed.count { it.themeId != t.id && it.label == t.name }
                var open by remember(problems) { mutableStateOf(problems > 0) }
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Installed icon packs · ${installed.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (problems > 0) Text("$problems same name", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 8.dp))
                        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) "Hide" else "Show")
                    }
                    if (open) Column(Modifier.padding(bottom = 8.dp)) {
                        installed.forEach { p ->
                            val mine = p.themeId == t.id
                            val sameName = !mine && p.label == t.name
                            val status = when {
                                mine -> "This theme's pack"
                                sameName && p.updatable -> "Same name — updated by this theme's next export"
                                sameName -> "Same name, old copy that can't be updated — remove it"
                                p.themeId != null -> "Theme \"${themes.firstOrNull { it.id == p.themeId }?.name ?: "?"}\""
                                !p.updatable -> "Old copy, can't be updated"
                                else -> "Not linked to a theme"
                            }
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.label + (p.versionName?.let { " · v$it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                                    Text(status, style = MaterialTheme.typography.labelSmall, color = when {
                                        mine -> MaterialTheme.colorScheme.primary
                                        sameName && !p.updatable -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    })
                                }
                                if (!mine) TextButton(onClick = { vm.uninstall(p.packageName) }) { Text("Remove") }
                            }
                        }
                    }
                }
            }

            // ---- options
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text("Options", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    OptionLabel("Icon shape · adaptive icons")
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconShape.entries.forEach { s ->
                            FilterChip(t.iconShape == s.name, { vm.setIconShape(s.name) },
                                { Text(if (s == IconShape.DEFAULT) "Default" else s.label, maxLines = 1, softWrap = false) })
                        }
                    }
                    if (t.iconShape != "DEFAULT") {
                        val all by vm.settings.shapeAllIcons.collectAsState()
                        SwitchRow("Shape every icon", "Also icons that aren't adaptive", all) { vm.settings.setShapeAllIcons(it) }
                    }
                    OptionLabel("Icon size")
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(128 to "Small", 192 to "Standard", 256 to "Sharp").forEach { (px, label) ->
                            FilterChip(size == px, { vm.settings.setExportSize(px) }, { Text("$label · $px", maxLines = 1, softWrap = false) })
                        }
                    }
                    Gap(h = 4.dp)
                    val live by vm.settings.liveColours.collectAsState()
                    SwitchRow(
                        "Live Material You colours",
                        if (t.iconShape != "DEFAULT") "Experimental · adaptive icons use the shape instead"
                        else "Experimental · icons follow your wallpaper colours",
                        live,
                    ) { vm.settings.setLiveColours(it) }
                    SwitchRow("Include the whole base pack", "Themes apps you install later · bigger file", whole) {
                        vm.settings.setIncludeWholePack(it)
                    }
                }
            }

            // ---- ZIP
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.FolderZip, null); Gap(w = 12.dp)
                    Column(Modifier.weight(1f)) {
                        Text("ZIP backup", style = MaterialTheme.typography.titleSmall)
                        Text("Icons + theme in one file, to import later", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { saveZip.launch(vm.fileName(t) + ".zip") }, enabled = !working) { Text("Save") }
                }
            }

            // ---- how to apply (folded)
            var howOpen by remember { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.fillMaxWidth().clickable { howOpen = !howOpen }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null); Gap(w = 8.dp)
                    Text("How to apply it", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(if (howOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
                if (howOpen) Text(
                    "• ColorOS / OxygenOS: long-press the home screen → Icons (or Wallpapers & style → Icons) → pick “${t.name}”.\n" +
                        "• Nova / Lawnchair / Smart / Niagara: launcher settings → Icons → Icon pack.\n" +
                        "• One UI: Good Lock → Theme Park → Icon.\n" +
                        "• No icon-pack option in your launcher? Use one of the launchers above.\n" +
                        "After changing icons here, build and install again.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun OptionLabel(text: String) = Text(text, style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))

/** One-line title + short hint + switch; the whole row toggles. */
@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Gap(w = 12.dp)
        Switch(checked, onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s = vm.a.settings
    val mode by s.themeMode.collectAsState()
    val black by s.pureBlack.collectAsState()
    val dynamic by s.dynamicColor.collectAsState()
    val seed by s.seedColor.collectAsState()
    val notify by s.notifyNewApps.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            SectionTitle("Appearance")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Settings.MODE_SYSTEM to "System", Settings.MODE_LIGHT to "Light", Settings.MODE_DARK to "Dark").forEach { (m, l) ->
                    FilterChip(mode == m, { s.setThemeMode(m) }, { Text(l) })
                }
            }
            Text("Colour", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp))
            ColourPicker(
                wallpaper = dynamic && android.os.Build.VERSION.SDK_INT >= 31,
                seed = seed,
                onWallpaper = { s.setDynamicColor(true) },
                onColour = { s.setSeedColor(it) },
            )
            ListItem(
                modifier = Modifier.clickable { s.setPureBlack(!black) },
                leadingContent = { Icon(Icons.Outlined.Contrast, null) },
                headlineContent = { Text("Pure black dark theme") },
                supportingContent = { Text("True black backgrounds for AMOLED screens") },
                trailingContent = { Switch(black, { s.setPureBlack(it) }) },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("New apps")
            ListItem(
                modifier = Modifier.clickable { s.setNotifyNewApps(!notify) },
                headlineContent = { Text("Notify about unthemed new apps") },
                supportingContent = { Text("When you install an app, Icon Mixer fills its icon from your theme's packs. If none fits, you get a notification to pick one.") },
                trailingContent = { Switch(notify, { s.setNotifyNewApps(it) }) },
            )
            val autoUpdate by s.autoUpdatePack.collectAsState()
            ListItem(
                modifier = Modifier.clickable { s.setAutoUpdatePack(!autoUpdate) },
                headlineContent = { Text("Keep the installed pack up to date") },
                supportingContent = { Text("When new apps get icons, rebuild your installed icon pack and update it. On Android 12+ this happens without asking.") },
                trailingContent = { Switch(autoUpdate, { s.setAutoUpdatePack(it) }) },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("About")
            ListItem(
                headlineContent = { Text("Icon Mixer 1.9.1") },
                supportingContent = {
                    Text("Mixes icons from the packs you own into one complete pack. Exported packs are signed with a key kept on this phone; " +
                        "if you reinstall Icon Mixer, uninstall old exported packs before installing new ones.")
                },
            )
        }
    }
}

/** Wallpaper colours (Android 12+) or one of the app colours; the chosen one shows a tick. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColourPicker(wallpaper: Boolean, seed: Int, onWallpaper: () -> Unit, onColour: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            Swatch(selected = wallpaper, fill = cs.surfaceContainerHighest, label = "Wallpaper colours", onClick = onWallpaper) {
                Icon(Icons.Outlined.Wallpaper, null, tint = if (wallpaper) cs.onPrimary else cs.onSurfaceVariant)
            }
        }
        APP_COLOURS.forEach { (name, c) ->
            val on = !wallpaper && seed == c
            Swatch(selected = on, fill = androidx.compose.ui.graphics.Color(c), label = name, onClick = { onColour(c) }) {
                if (on) Icon(Icons.Outlined.Check, null, tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

@Composable
private fun Swatch(
    selected: Boolean, fill: androidx.compose.ui.graphics.Color, label: String, onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Box(
        Modifier.size(48.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (selected && fill == cs.surfaceContainerHighest) cs.primary else fill)
            .then(if (selected) Modifier.border(3.dp, cs.outline, androidx.compose.foundation.shape.CircleShape) else Modifier)
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

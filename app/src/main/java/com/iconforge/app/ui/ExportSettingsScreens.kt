package com.iconforge.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(
                "${t.themedApps} of ${t.totalApps} apps have icons (${(t.coverage * 100).toInt()}%). " +
                    "Apps without one keep their normal icon (or the launcher's fallback).",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Gap(h = 12.dp)

            // ---- APK
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Android, null); Gap(w = 8.dp)
                        Text("Icon pack APK", style = MaterialTheme.typography.titleMedium)
                    }
                    Gap(h = 6.dp)
                    Text(
                        "A real icon pack with every icon in this theme: pack icons, your manual picks and kept originals. " +
                            "Install it, then choose “${t.name}” as the icon pack in your launcher." +
                            if (vm.isInstalled(t)) "\n\nAlready installed — installing again updates it in place." else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Gap(h = 12.dp)
                    Button(onClick = vm::buildApk, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                        Text(if (ready != null) "Build again" else "Build icon pack")
                    }
                    if (ready != null) {
                        Gap(h = 8.dp)
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
                        Text(
                            "${ready.length() / 1024} KB · ${vm.packageName(t)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            Gap(h = 12.dp)

            // ---- ZIP
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.FolderZip, null); Gap(w = 8.dp)
                        Text("ZIP backup", style = MaterialTheme.typography.titleMedium)
                    }
                    Gap(h = 6.dp)
                    Text("Icons + mappings + theme details in one file. Import it later or on another phone (also works with exported APKs).",
                        style = MaterialTheme.typography.bodyMedium)
                    Gap(h = 12.dp)
                    OutlinedButton(onClick = { saveZip.launch(vm.fileName(t) + ".zip") }, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                        Text("Save ZIP backup")
                    }
                }
            }

            // ---- progress / result
            when (val s = state) {
                is ExportViewModel.State.Working -> Column(Modifier.padding(vertical = 16.dp)) {
                    Text(s.text, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                is ExportViewModel.State.Done -> Text(s.text, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 16.dp))
                is ExportViewModel.State.Failed -> Text(s.text, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 16.dp))
                else -> Gap(h = 16.dp)
            }

            // ---- options
            Text("Options", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text("Icon shape (adaptive icons)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconShape.entries.forEach { s ->
                    FilterChip(t.iconShape == s.name, { vm.setIconShape(s.name) }, { Text(s.label, maxLines = 1, softWrap = false) })
                }
            }
            Text("Icon size", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            // FlowRow wraps on narrow screens instead of squeezing the last chip.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(128 to "Small", 192 to "Standard", 256 to "Sharp").forEach { (px, label) ->
                    FilterChip(size == px, { vm.settings.setExportSize(px) }, { Text("$label · $px px", maxLines = 1, softWrap = false) })
                }
            }
            val live by vm.settings.liveColours.collectAsState()
            ListItem(
                modifier = Modifier.clickable { vm.settings.setLiveColours(!live) },
                headlineContent = { Text("Live Material You colours (experimental)") },
                supportingContent = {
                    Text("Icons from Material You packs keep following your wallpaper colours instead of being saved as pictures. " +
                        "Icon shape and size adjustments don't apply to them. If your launcher shows them wrong, turn this off and export again.")
                },
                trailingContent = { Switch(live, { vm.settings.setLiveColours(it) }) },
            )
            ListItem(
                modifier = Modifier.clickable { vm.settings.setIncludeWholePack(!whole) },
                headlineContent = { Text("Include the whole base pack") },
                supportingContent = { Text("Also copies every icon of the base pack, so apps you install later are themed without re-exporting. Bigger and slower.") },
                trailingContent = { Switch(whole, { vm.settings.setIncludeWholePack(it) }) },
            )

            // ---- how to apply
            Card(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, null); Gap(w = 8.dp)
                        Text("Applying it", style = MaterialTheme.typography.titleSmall)
                    }
                    Gap(h = 6.dp)
                    Text(
                        "• ColorOS / OxygenOS: long-press the home screen → Icons (or Wallpapers & style → Icons) → pick “${t.name}”.\n" +
                            "• Nova / Lawnchair / Smart / Niagara: launcher settings → Look & feel / Icons → Icon pack.\n" +
                            "• One UI: Good Lock → Theme Park → Icon → choose the pack.\n" +
                            "• If your phone's own launcher has no icon-pack option, use one of the launchers above.\n\n" +
                            "After changing icons here, export and install again — the launcher picks up the update.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s = vm.a.settings
    val mode by s.themeMode.collectAsState()
    val black by s.pureBlack.collectAsState()
    val dynamic by s.dynamicColor.collectAsState()
    val notify by s.notifyNewApps.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            SectionTitle("Appearance")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Settings.MODE_SYSTEM to "System", Settings.MODE_LIGHT to "Light", Settings.MODE_DARK to "Dark").forEach { (m, l) ->
                    FilterChip(mode == m, { s.setThemeMode(m) }, { Text(l) })
                }
            }
            ListItem(
                modifier = Modifier.clickable { s.setPureBlack(!black) },
                headlineContent = { Text("Pure black") },
                supportingContent = { Text("Black background in dark mode (AMOLED)") },
                trailingContent = { Switch(black, { s.setPureBlack(it) }) },
            )
            ListItem(
                modifier = Modifier.clickable { s.setDynamicColor(!dynamic) },
                headlineContent = { Text("Wallpaper colours") },
                supportingContent = { Text("Material You dynamic colour (Android 12+)") },
                trailingContent = { Switch(dynamic, { s.setDynamicColor(it) }) },
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
                headlineContent = { Text("Icon Mixer 1.4") },
                supportingContent = {
                    Text("Mixes icons from the packs you own into one complete pack. Exported packs are signed with a key kept on this phone; " +
                        "if you reinstall Icon Mixer, uninstall old exported packs before installing new ones.")
                },
            )
        }
    }
}

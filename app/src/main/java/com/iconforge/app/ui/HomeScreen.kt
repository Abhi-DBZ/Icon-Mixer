package com.iconforge.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.iconforge.app.Routes
import com.iconforge.app.core.Coverage
import com.iconforge.app.core.IconSrc
import com.iconforge.app.data.IconPackEntity
import com.iconforge.app.data.ThemeEntity
import com.iconforge.app.switchTab
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, nav: NavHostController) {
    val themes by vm.themes.collectAsState()
    val active by vm.activeTheme.collectAsState()
    val coverage by vm.activeCoverage.collectAsState()
    val apps by vm.apps.collectAsState()
    val packs by vm.packs.collectAsState()
    val busy by vm.busy.collectAsState()
    val scanned by vm.scanned.collectAsState()
    val update by vm.update.collectAsState()
    var showNew by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ThemeEntity?>(null) }
    var deleting by remember { mutableStateOf<ThemeEntity?>(null) }
    var autoFillFor by remember { mutableStateOf<Long?>(null) }
    val activeMaps by vm.activeMappings.collectAsState()
    val reviewCount = activeMaps.values.count { it.isGuess }
    val ctx = LocalContext.current

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri)
    }
    val newApps = apps.filter { it.isNew }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Icon Mixer") },
                actions = {
                    IconButton(onClick = { importer.launch(arrayOf("application/zip", "application/vnd.android.package-archive", "application/octet-stream")) }) {
                        Icon(Icons.Outlined.FileUpload, "Import theme")
                    }
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Outlined.Refresh, "Rescan") }
                },
            )
        },
        floatingActionButton = {
            if (packs.isNotEmpty()) ExtendedFloatingActionButton(
                onClick = { showNew = true },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("New theme") },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 96.dp)) {
            // New app version: stays until it's installed.
            val upd = update
            if (upd is MainViewModel.Update.Available || upd is MainViewModel.Update.Downloading) item {
                androidx.compose.material3.Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.SystemUpdate, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Gap(w = 12.dp)
                        Column(Modifier.weight(1f)) {
                            if (upd is MainViewModel.Update.Available) {
                                Text("Icon Mixer ${upd.release.version} is available", style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text("Your themes and packs stay as they are", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                            } else if (upd is MainViewModel.Update.Downloading) {
                                Text("Downloading update…", style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                                LinearProgressIndicator(progress = { upd.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                            }
                        }
                        if (upd is MainViewModel.Update.Available) {
                            Gap(w = 8.dp)
                            Button(onClick = { vm.installUpdate(upd.release) }) { Text("Update") }
                        }
                    }
                }
            }
            busy?.let { msg ->
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(msg, style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
            }

            val t = active
            if (t == null) {
                item {
                    if (!scanned) Box(Modifier.fillMaxWidth().padding(48.dp)) {}
                    else if (packs.isEmpty()) EmptyState(
                        Icons.Outlined.Palette, "No icon packs found",
                        "Install any icon pack from the Play Store (one that works with Nova or Lawnchair), then come back. Icon Mixer reads its icons and fills the gaps.",
                    ) { Button(onClick = { vm.refresh() }) { Text("Scan again") } }
                    else EmptyState(
                        Icons.Outlined.AutoFixHigh, "Create your first theme",
                        "Pick one of your ${packs.size} icon packs. Icon Mixer applies every icon it has for your ${apps.size} apps, then helps you fill in the rest.",
                    ) { Button(onClick = { showNew = true }) { Text("Choose an icon pack") } }
                }
            } else {
                item { ActiveThemeCard(t, coverage, packs, reviewCount, nav, vm) { autoFillFor = t.id } }
                if (newApps.isNotEmpty()) item {
                    // One quiet row: new apps' icons, Review, and a clear close button.
                    Card(
                        onClick = { nav.navigate(Routes.editor(t.id, "NEW")) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            newApps.take(3).forEach { IconImage(IconSrc.original(it), 28.dp, Modifier.padding(end = 4.dp)) }
                            Gap(w = 6.dp)
                            Text(
                                if (newApps.size == 1) "${newApps[0].label} is new" else "${newApps.size} new apps",
                                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { nav.navigate(Routes.editor(t.id, "NEW")) }) { Text("Review") }
                            IconButton(onClick = { vm.markAllSeen() }) {
                                Icon(Icons.Outlined.Close, "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            if (themes.isNotEmpty()) {
                item { SectionTitle("Your themes") }
                items(themes, key = { it.id }) { th ->
                    ThemeRow(
                        theme = th, active = th.id == active?.id,
                        onClick = { vm.setActive(th.id) },
                        onEdit = { nav.navigate(Routes.editor(th.id)) },
                        onRename = { renaming = th },
                        onDuplicate = { vm.duplicate(th.id) },
                        onExport = { nav.navigate(Routes.export(th.id)) },
                        onDelete = { deleting = th },
                        onPreview = { nav.navigate(Routes.preview(th.id)) },
                        onShare = { vm.shareTheme(th.id) { f -> shareFile(ctx, f, "application/zip", "Share theme") } },
                    )
                }
            }
        }
    }

    if (showNew) NewThemeDialog(packs, onDismiss = { showNew = false }) { name, pack ->
        showNew = false
        vm.createTheme(name, pack) { id -> nav.navigate(Routes.editor(id, "MISSING")) }
    }
    autoFillFor?.let { id ->
        val packLabel = themes.firstOrNull { it.id == id }?.basePack?.let { b -> packs.firstOrNull { it.packageName == b }?.label }
        AutoFillDialog(packLabel, onDismiss = { autoFillFor = null }) { g, gen, others ->
            autoFillFor = null
            vm.smartFill(id, g, gen, others) { toReview -> if (toReview > 0) nav.navigate(Routes.editor(id, "REVIEW")) }
        }
    }
    renaming?.let { th ->
        TextDialog("Rename theme", th.name, onDismiss = { renaming = null }) { vm.rename(th.id, it); renaming = null }
    }
    deleting?.let { th ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${th.name}\"?") },
            text = { Text("Its icon assignments are removed from Icon Mixer. An icon pack you already installed from it stays on the phone.") },
            confirmButton = { TextButton(onClick = { vm.delete(th.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ActiveThemeCard(
    t: ThemeEntity, coverage: Coverage, packs: List<IconPackEntity>, reviewCount: Int,
    nav: NavHostController, vm: MainViewModel, onAutoFill: () -> Unit,
) {
    val base = packs.firstOrNull { it.packageName == t.basePack }
    // Raised card on a light surface: tinted buttons stay visible in light mode too.
    Card(
        onClick = { nav.navigate(Routes.editor(t.id)) },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (base?.let { "${it.label} · " } ?: "") + "${coverage.themed}/${coverage.total} apps",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (coverage.missing > 0) Text("${coverage.missing} missing", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    else Text("All apps themed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                CoverageRing(coverage, size = 60.dp)
            }
            if (t.exportCount > 0 && t.updatedAt > t.lastExportedAt) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Outlined.Sync, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                    Gap(w = 6.dp)
                    Text("Changed since last export", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f))
                    if (vm.isPackInstalled(t)) TextButton(onClick = { vm.updatePackNow(t.id) }) { Text("Update now") }
                }
            }
            if (reviewCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoFixHigh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Gap(w = 6.dp)
                    Text("$reviewCount auto-filled icons to review", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    TextButton(onClick = { nav.navigate(Routes.editor(t.id, "REVIEW")) }) { Text("Review") }
                }
            }
            Gap(h = 10.dp)
            val compact = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
            val h = Modifier.height(36.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (coverage.missing > 0) {
                    Button(onClick = onAutoFill, modifier = h.weight(1.2f), contentPadding = compact) {
                        Icon(Icons.Outlined.AutoFixHigh, null, Modifier.size(16.dp)); Gap(w = 4.dp); Text("Auto-fill", maxLines = 1)
                    }
                    FilledTonalButton(onClick = { nav.navigate(Routes.editor(t.id, "MISSING")) }, modifier = h.weight(1f), contentPadding = compact) {
                        Text("Fix ${coverage.missing}", maxLines = 1)
                    }
                } else {
                    FilledTonalButton(onClick = { nav.navigate(Routes.preview(t.id)) }, modifier = h.weight(1f), contentPadding = compact) {
                        Text("Preview", maxLines = 1)
                    }
                    FilledTonalButton(onClick = { nav.navigate(Routes.editor(t.id)) }, modifier = h.weight(1f), contentPadding = compact) {
                        Text("Edit", maxLines = 1)
                    }
                }
                FilledTonalButton(onClick = { nav.navigate(Routes.export(t.id)) }, modifier = h.weight(1f), contentPadding = compact) { Text("Export", maxLines = 1) }
            }
            if (packs.isEmpty()) TextButton(onClick = { nav.switchTab(Routes.PACKS) }) { Text("No icon packs installed") }
        }
    }
}

@Composable
private fun ThemeRow(
    theme: ThemeEntity, active: Boolean, onClick: () -> Unit, onEdit: () -> Unit, onRename: () -> Unit,
    onDuplicate: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit, onPreview: () -> Unit, onShare: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // Short date ("26 Nov"); the year only when it isn't this year.
    val date = remember(theme.updatedAt) {
        val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val then = java.util.Calendar.getInstance().apply { timeInMillis = theme.updatedAt }.get(java.util.Calendar.YEAR)
        java.text.SimpleDateFormat(if (now == then) "d MMM" else "d MMM yyyy", java.util.Locale.getDefault()).format(Date(theme.updatedAt))
    }
    val cs = MaterialTheme.colorScheme
    val rowShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(rowShape)
            .background(if (active) cs.secondaryContainer else cs.surfaceContainerLow)
            .border(if (active) 1.5.dp else 1.dp, if (active) cs.primary else cs.outlineVariant, rowShape)
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 0.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = active, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(theme.name, style = MaterialTheme.typography.titleSmall,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${(theme.coverage * 100).toInt()}% · ${theme.themedApps}/${theme.totalApps} apps · $date",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (theme.exportCount > 0) {
                    Gap(w = 6.dp)
                    Icon(Icons.Outlined.CheckCircle, "Exported", tint = cs.primary, modifier = Modifier.size(14.dp))
                }
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Theme options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Edit icons") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = { menu = false; onDuplicate() })
                DropdownMenuItem(text = { Text("Preview") }, leadingIcon = { Icon(Icons.Outlined.Visibility, null) }, onClick = { menu = false; onPreview() })
                DropdownMenuItem(text = { Text("Export") }, leadingIcon = { Icon(Icons.Outlined.FileDownload, null) }, onClick = { menu = false; onExport() })
                DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = { menu = false; onShare() })
                DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
fun NewThemeDialog(packs: List<IconPackEntity>, preselect: String? = null, onDismiss: () -> Unit, onCreate: (String, String?) -> Unit) {
    var pack by remember { mutableStateOf(preselect ?: packs.maxByOrNull { it.mappedComponents }?.packageName) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New theme") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Name") },
                    placeholder = { Text(packs.firstOrNull { it.packageName == pack }?.label ?: "My theme") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                Gap(h = 12.dp)
                Text("Start from icon pack", style = MaterialTheme.typography.labelLarge)
                packs.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = pack == p.packageName, onClick = { pack = p.packageName })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = pack == p.packageName, onClick = { pack = p.packageName })
                        Column {
                            Text(p.label)
                            Text("${p.totalIcons} icons", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().selectable(selected = pack == null, onClick = { pack = null }).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = pack == null, onClick = { pack = null })
                    Text("Empty (assign everything by hand)")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCreate(name.ifBlank { packs.firstOrNull { it.packageName == pack }?.label ?: "My theme" }, pack)
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onOk(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Choose what Auto-fill may use. Guesses and generated icons are marked "to review". */
@Composable
fun AutoFillDialog(packLabel: String?, onDismiss: () -> Unit, onRun: (guesses: Boolean, generate: Boolean, otherPacks: Boolean) -> Unit) {
    val pack = packLabel ?: "your theme's pack"
    var guesses by remember { mutableStateOf(true) }
    var generate by remember { mutableStateOf(true) }
    var others by remember { mutableStateOf(false) }
    @Composable
    fun option(checked: Boolean, onChange: (Boolean) -> Unit, title: String, body: String) {
        Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
            androidx.compose.material3.Checkbox(checked, onChange)
            Column(Modifier.padding(top = 10.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto-fill missing icons") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Uses only $pack, so the theme keeps one look.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Gap(h = 4.dp)
                option(guesses, { guesses = it }, "Best guesses by name", "Icons in $pack whose name fits the app, e.g. \"bank\" for a banking app. You review these.")
                option(generate, { generate = it }, "Generate the rest", "The app's own icon redrawn in $pack's style. You review these.")
                option(others, { others = it }, "Also use my other icon packs", "Takes icons from your other packs too. Fills more apps, but mixes styles.")
            }
        },
        confirmButton = { TextButton(onClick = { onRun(guesses, generate, others) }) { Text("Fill") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Opens the share sheet for a file in the app's cache. */
fun shareFile(ctx: android.content.Context, f: java.io.File, mime: String, title: String) {
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType(mime)
        .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(android.content.Intent.createChooser(send, title))
}

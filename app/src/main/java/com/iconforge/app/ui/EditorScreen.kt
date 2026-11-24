package com.iconforge.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.runtime.CompositionLocalProvider
import com.iconforge.app.core.IconShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.iconforge.app.Routes
import com.iconforge.app.app
import com.iconforge.app.core.IconSrc
import com.iconforge.app.data.IconPackEntity
import com.iconforge.app.data.Source

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(main: MainViewModel, nav: NavHostController, themeId: Long, initialFilter: String) {
    val ctx = LocalContext.current
    val vm: EditorViewModel = viewModel(key = "editor-$themeId") { EditorViewModel(ctx.app, themeId) }
    // Apply the requested filter once, not again when returning from the picker.
    var filterApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialFilter) {
        if (!filterApplied) {
            vm.filter.value = runCatching { EditorViewModel.Filter.valueOf(initialFilter) }.getOrDefault(EditorViewModel.Filter.ALL)
            filterApplied = true
        }
    }
    val theme by vm.theme.collectAsState()
    val rows by vm.rows.collectAsState()
    val counts by vm.counts.collectAsState()
    val filter by vm.filter.collectAsState()
    val query by vm.query.collectAsState()
    val sel by vm.selection.collectAsState()
    val undo by main.undoLabel.collectAsState()
    val packs by main.packs.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var packChooser by remember { mutableStateOf<PackAction?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val selecting = sel.isNotEmpty()
    val listView by main.a.settings.editorList.collectAsState()
    val shape = IconShape.of(theme?.iconShape)
    var shapeDialog by remember { mutableStateOf(false) }
    var autoFill by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (selecting) IconButton(onClick = vm::clearSelection) { Icon(Icons.Outlined.Close, "Clear selection") }
                    else IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                },
                title = {
                    if (selecting) Text("${sel.size} selected")
                    else Column {
                        Text(theme?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        theme?.let {
                            Text("${it.themedApps}/${it.totalApps} themed · ${(it.coverage * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = vm::selectAllVisible) { Icon(Icons.Outlined.SelectAll, "Select all") }
                    } else {
                        IconButton(onClick = { main.a.settings.setEditorList(!listView) }) {
                            Icon(if (listView) Icons.Outlined.GridView else Icons.AutoMirrored.Outlined.ViewList,
                                if (listView) "Grid view" else "List view")
                        }
                        IconButton(onClick = main::undo, enabled = undo != null) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo ${undo ?: ""}") }
                        IconButton(onClick = { nav.navigate(Routes.export(themeId)) }) { Icon(Icons.Outlined.FileDownload, "Export") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Auto-fill missing…") }, onClick = { menu = false; autoFill = true })
                                if ((counts[EditorViewModel.Filter.REVIEW] ?: 0) > 0) DropdownMenuItem(
                                    text = { Text("Keep all auto-filled") }, onClick = { menu = false; main.acceptAll(themeId) })
                                DropdownMenuItem(text = { Text("Preview on home screen") }, onClick = { menu = false; nav.navigate(Routes.preview(themeId)) })
                                DropdownMenuItem(text = { Text("Fill missing from a pack…") }, onClick = { menu = false; packChooser = PackAction.FILL })
                                DropdownMenuItem(text = { Text("Apply a pack to all…") }, onClick = { menu = false; packChooser = PackAction.APPLY })
                                DropdownMenuItem(text = { Text("Change base pack…") }, onClick = { menu = false; packChooser = PackAction.BASE })
                                DropdownMenuItem(text = { Text("Icon shape: ${shape.label}…") }, onClick = { menu = false; shapeDialog = true })
                                DropdownMenuItem(text = { Text("Select all shown") }, onClick = { menu = false; vm.selectAllVisible() })
                                DropdownMenuItem(text = { Text("Reset entire theme") }, onClick = { menu = false; confirmReset = true })
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selecting) BottomAppBar {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { nav.navigate(Routes.picker(themeId, sel)) }) { Text("Pick one icon") }
                    TextButton(onClick = { packChooser = PackAction.SELECTED }) { Text("From pack…") }
                    if (rows.any { it.app.componentKey in sel && it.mapping?.isGuess == true }) TextButton(onClick = vm::accept) { Text("Keep") }
                    TextButton(onClick = {
                        scope.launch {
                            val p = stylePackFor(main, theme?.basePack)
                            if (p != null) vm.generate(p) else main.say("No icon pack to take a style from")
                        }
                    }) { Text("Generate") }
                    TextButton(onClick = vm::keepOriginal) { Text("Keep original") }
                    TextButton(onClick = vm::reset) { Text("Reset") }
                    TextButton(onClick = vm::clearIcons) { Text("Remove") }
                }
            }
        },
    ) { pad ->
      CompositionLocalProvider(LocalIconShape provides shape) {
        Column(Modifier.fillMaxSize().padding(pad)) {
            SearchField(query, { vm.query.value = it }, "Search apps", Modifier.padding(horizontal = 16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorViewModel.Filter.entries.forEach { f ->
                    val n = counts[f] ?: 0
                    if (f == EditorViewModel.Filter.NEW && n == 0 && filter != f) return@forEach
                    FilterChip(filter == f, { vm.filter.value = f }, { Text("${f.label} · $n") })
                }
            }
            if (filter == EditorViewModel.Filter.REVIEW && rows.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-filled icons. Tap one to change it, or keep them all.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { main.acceptAll(themeId) }) { Text("Keep all") }
                }
            }
            if (rows.isEmpty()) {
                if (filter == EditorViewModel.Filter.MISSING && query.isBlank()) {
                    EmptyState(Icons.Outlined.CheckCircle, "Every app has an icon",
                        "This theme covers all your apps. Export it to use it in your launcher.") {
                        TextButton(onClick = { nav.navigate(Routes.export(themeId)) }) { Text("Export") }
                    }
                } else Text("Nothing here", Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val open: (String) -> Unit = { key -> if (selecting) vm.toggle(key) else nav.navigate(Routes.picker(themeId, listOf(key))) }
            if (listView) {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(rows, key = { it.app.componentKey }) { r -> EditorListRow(r, r.app.componentKey in sel, { open(r.app.componentKey) }, { vm.toggle(r.app.componentKey) }) }
                }
            } else LazyVerticalGrid(
                columns = GridCells.Adaptive(84.dp),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp),
            ) {
                items(rows, key = { it.app.componentKey }) { r -> EditorGridCell(r, r.app.componentKey in sel, { open(r.app.componentKey) }, { vm.toggle(r.app.componentKey) }) }
            }
        }
      }
    }

    if (autoFill) AutoFillDialog(onDismiss = { autoFill = false }) { m, g, gen ->
        autoFill = false
        main.smartFill(themeId, m, g, gen) { n -> if (n > 0) vm.filter.value = EditorViewModel.Filter.REVIEW }
    }

    if (shapeDialog) ShapeDialog(shape, rows.firstOrNull()?.app?.let { IconSrc.original(it) }, onDismiss = { shapeDialog = false }) {
        shapeDialog = false; main.setIconShape(themeId, it)
    }

    packChooser?.let { action ->
        PackChooserDialog(action, packs, onDismiss = { packChooser = null }) { pack ->
            packChooser = null
            when (action) {
                PackAction.FILL -> main.fillMissing(themeId, pack.packageName)
                PackAction.APPLY -> main.applyPack(themeId, pack.packageName)
                PackAction.BASE -> main.setBasePack(themeId, pack.packageName)
                PackAction.SELECTED -> vm.fromPack(pack.packageName) { n ->
                    main.say(if (n == 0) "${pack.label} has no icons for those apps" else "$n icons from ${pack.label}")
                }
            }
        }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset entire theme?") },
        text = { Text("All manual choices are dropped and icons are re-matched from the base pack. You can undo this.") },
        confirmButton = { TextButton(onClick = { confirmReset = false; main.resetTheme(themeId) }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
}

enum class PackAction(val title: String) {
    FILL("Fill missing icons from"), APPLY("Apply icons from"), BASE("Base pack"), SELECTED("Icons for selected apps from")
}

@Composable
private fun PackChooserDialog(action: PackAction, packs: List<IconPackEntity>, onDismiss: () -> Unit, onPick: (IconPackEntity) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action.title) },
        text = {
            Column {
                if (packs.isEmpty()) Text("No icon packs installed.")
                packs.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(p) }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconImage(IconSrc.App(p.packageName, ""), 36.dp)
                        Gap(w = 12.dp)
                        Column {
                            Text(p.label)
                            Text("${p.totalIcons} icons", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Base pack if it has a style kit, else any installed pack that does. */
suspend fun stylePackFor(main: MainViewModel, base: String?): String? {
    val all = main.packs.value.map { it.packageName }
    return (listOfNotNull(base) + all).firstOrNull { main.a.packs.load(it)?.hasStyle == true } ?: base ?: all.firstOrNull()
}

private fun statusOf(r: AppRow) = when {
    r.mapping == null -> "missing"
    r.mapping.source == Source.GENERATED -> if (r.mapping.isGuess) "generated ?" else "generated"
    r.mapping.isGuess -> "guess ?"
    r.mapping.source == Source.ORIGINAL -> "original"
    r.mapping.source == Source.CUSTOM -> "image"
    r.mapping.isManual -> "manual"
    else -> "auto"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditorGridCell(r: AppRow, picked: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier.padding(4.dp).clip(RoundedCornerShape(16.dp))
            .background(if (picked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            if (r.mapping == null) MissingSlot(52.dp) else IconImage(IconSrc.of(r.mapping, r.app), 52.dp)
            // original icon as a small badge (unshaped)
            IconImage(IconSrc.original(r.app), 20.dp, Modifier.align(Alignment.BottomEnd), shape = IconShape.DEFAULT)
            if (picked) SelectedTick(Modifier.align(Alignment.TopStart))
        }
        Gap(h = 6.dp)
        Text(r.app.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center)
        Text(statusOf(r), style = MaterialTheme.typography.labelSmall,
            color = if (r.mapping == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditorListRow(r: AppRow, picked: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (picked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            IconImage(IconSrc.original(r.app), 40.dp, shape = IconShape.DEFAULT)
            if (picked) SelectedTick(Modifier.align(Alignment.TopStart))
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.padding(horizontal = 8.dp).size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r.mapping == null) MissingSlot(40.dp) else IconImage(IconSrc.of(r.mapping, r.app), 40.dp)
        Gap(w = 14.dp)
        Column(Modifier.weight(1f)) {
            Text(r.app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(r.app.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(statusOf(r), style = MaterialTheme.typography.labelMedium,
            color = if (r.mapping == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SelectedTick(modifier: Modifier) = Icon(Icons.Outlined.Done, null,
    modifier.size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(2.dp),
    tint = MaterialTheme.colorScheme.onPrimary)

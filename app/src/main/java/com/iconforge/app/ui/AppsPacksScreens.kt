package com.iconforge.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.iconforge.app.Routes
import com.iconforge.app.app
import com.iconforge.app.core.Coverage
import com.iconforge.app.core.IconSrc
import com.iconforge.app.data.IconPackEntity

// ------------------------------------------------------------------ apps

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(vm: MainViewModel, nav: NavHostController) {
    val apps by vm.apps.collectAsState()
    val maps by vm.activeMappings.collectAsState()
    val theme by vm.activeTheme.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(0) }    // 0 all, 1 user, 2 system
    var status by rememberSaveable { mutableStateOf(0) }  // 0 all, 1 themed, 2 missing

    val q = query.trim().lowercase()
    val list = apps.filter { a ->
        (kind == 0 || (kind == 1) == !a.isSystem) &&
            (status == 0 || (status == 1) == (a.componentKey in maps)) &&
            (q.isEmpty() || a.label.lowercase().contains(q) || a.packageName.contains(q))
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Installed apps") }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            SearchField(query, { query = it }, "Search apps", Modifier.padding(horizontal = 16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(kind == 1, { kind = if (kind == 1) 0 else 1 }, { Text("User · ${apps.count { !it.isSystem }}") })
                FilterChip(kind == 2, { kind = if (kind == 2) 0 else 2 }, { Text("System · ${apps.count { it.isSystem }}") })
                if (theme != null) {
                    FilterChip(status == 1, { status = if (status == 1) 0 else 1 }, { Text("Themed") })
                    FilterChip(status == 2, { status = if (status == 2) 0 else 2 }, { Text("Missing icon") })
                }
            }
            if (theme == null) Text(
                "Create a theme to see which apps have icons.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(list, key = { it.componentKey }) { app ->
                    val m = maps[app.componentKey]
                    ListItem(
                        modifier = Modifier.clickable(enabled = theme != null) {
                            theme?.let { nav.navigate(Routes.picker(it.id, listOf(app.componentKey))) }
                        },
                        leadingContent = { IconImage(IconSrc.original(app), 44.dp) },
                        headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(app.packageName + if (app.isSystem) " · system" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = if (theme == null) null else {
                            {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Gap(w = 6.dp)
                                    if (m == null) MissingSlot(44.dp) else IconImage(IconSrc.of(m, app), 44.dp,
                                        shape = com.iconforge.app.core.IconShape.of(theme?.iconShape))
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    // Slim pill (44dp) instead of a 56dp outlined field: leaves more room for the list.
    val focus = LocalFocusManager.current
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().height(44.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(cs.surfaceContainerHigh)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
        Gap(w = 10.dp)
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            // The keyboard's search key closes the keyboard so all results are visible.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) IconButton(onClick = { onChange("") }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Close, "Clear", Modifier.size(18.dp))
        }
    }
}

// ------------------------------------------------------------------ packs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacksScreen(vm: MainViewModel, nav: NavHostController) {
    val packs by vm.packs.collectAsState()
    val theme by vm.activeTheme.collectAsState()
    val busy by vm.busy.collectAsState()
    val ctx = LocalContext.current
    var newFrom by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Icon packs") }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
            busy?.let { item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) } }
            if (packs.isEmpty()) item {
                EmptyState(Icons.Outlined.Palette, "No icon packs installed",
                    "Icon Mixer works with any icon pack made for Nova, Lawnchair or similar launchers. Install one from the Play Store and it shows up here.") {
                    Button(onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=icon%20pack&c=apps"))) }
                    }) { Text("Find icon packs") }
                }
            }
            items(packs, key = { it.packageName }) { p ->
                PackCard(
                    p, hasTheme = theme != null, isBase = theme?.basePack == p.packageName,
                    onNewTheme = { newFrom = p.packageName },
                    onFill = { theme?.let { vm.fillMissing(it.id, p.packageName) } },
                    onApply = { theme?.let { vm.applyPack(it.id, p.packageName) } },
                    onMakeBase = { theme?.let { vm.setBasePack(it.id, p.packageName) } },
                )
            }
        }
    }
    newFrom?.let { pkg ->
        NewThemeDialog(packs, preselect = pkg, onDismiss = { newFrom = null }) { name, pack ->
            newFrom = null
            vm.createTheme(name, pack) { id -> nav.navigate(Routes.editor(id, "MISSING")) }
        }
    }
}

@Composable
private fun PackCard(
    p: IconPackEntity, hasTheme: Boolean, isBase: Boolean,
    onNewTheme: () -> Unit, onFill: () -> Unit, onApply: () -> Unit, onMakeBase: () -> Unit,
) {
    val ctx = LocalContext.current
    val a = ctx.app
    // Coverage over this phone's apps + a preview of icons it has for them.
    val info by produceState<Pair<Coverage, List<IconSrc>>?>(null, p.packageName, p.versionCode) {
        val pack = a.packs.load(p.packageName)
        val apps = a.db.apps().all()
        if (pack == null) { value = Coverage(apps.size, 0) to emptyList(); return@produceState }
        val matched = apps.mapNotNull { app -> pack.match(app.packageName, app.activityName)?.let { IconSrc.Pack(p.packageName, it) } }
        value = Coverage(apps.size, matched.size) to matched.distinctBy { it.drawable }.take(8)
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconImage(IconSrc.App(p.packageName, ""), 48.dp)
                Gap(w = 12.dp)
                Column(Modifier.weight(1f)) {
                    Text(p.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(p.author.ifBlank { null }, "${p.totalIcons} icons", if (isBase) "base of active theme" else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                info?.first?.let { c ->
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${c.percent}%", style = MaterialTheme.typography.titleLarge)
                        Text("${c.themed}/${c.total} apps", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val preview = info?.second
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                if (preview == null) repeat(8) { IconImage(null, 36.dp) }
                else preview.forEach { IconImage(it, 36.dp) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onNewTheme) { Text("New theme") }
                if (hasTheme) {
                    OutlinedButton(onClick = onFill) { Text("Fill missing") }
                    OutlinedButton(onClick = onApply) { Text("Apply all") }
                    if (!isBase) OutlinedButton(onClick = onMakeBase) { Text("Make base") }
                }
            }
        }
    }
}

package com.iconforge.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iconforge.app.core.Updater
import com.iconforge.app.data.Settings

/**
 * Settings as grouped lists: a heading per section, rounded rows with a coloured icon,
 * title + one-line hint, and a value with › or a switch on the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s = vm.a.settings
    val ctx = LocalContext.current
    val mode by s.themeMode.collectAsState()
    val black by s.pureBlack.collectAsState()
    val dynamic by s.dynamicColor.collectAsState()
    val seed by s.seedColor.collectAsState()
    val notify by s.notifyNewApps.collectAsState()
    val autoUpdate by s.autoUpdatePack.collectAsState()
    val autoCheck by s.autoUpdateCheck.collectAsState()
    val update by vm.update.collectAsState()
    val apps by vm.apps.collectAsState()
    val packs by vm.packs.collectAsState()
    val themes by vm.themes.collectAsState()
    var themeDialog by remember { mutableStateOf(false) }
    var colourDialog by remember { mutableStateOf(false) }
    var statsDialog by remember { mutableStateOf(false) }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    val wallpaperColours = dynamic && Build.VERSION.SDK_INT >= 31
    val modeName = when (mode) { Settings.MODE_LIGHT -> "Light"; Settings.MODE_DARK -> "Dark"; else -> "Follow system" }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        ) {
            Group("Appearance") {
                item(Icons.Outlined.DarkMode, "Theme", null, value = modeName) { themeDialog = true }
                item(Icons.Outlined.Palette, "Colour", if (wallpaperColours) "From your wallpaper" else colourName(seed),
                    trailing = { ColourDot(if (wallpaperColours) MaterialTheme.colorScheme.primary else Color(seed)); Chevron() }) { colourDialog = true }
                toggle(Icons.Outlined.Contrast, "Pure black dark theme", "True black backgrounds for AMOLED screens", black) { s.setPureBlack(it) }
            }
            Group("Icon packs") {
                item(Icons.Outlined.Sync, "Rescan apps and icon packs", "Find newly installed packs and apps") { vm.refresh() }
                item(Icons.Outlined.Insights, "Statistics", "${apps.size} apps · ${packs.size} packs · ${themes.size} themes") { statsDialog = true }
            }
            Group("New apps") {
                toggle(Icons.Outlined.NotificationsActive, "Notify about unthemed apps", "When a new app has no icon in your theme", notify) { s.setNotifyNewApps(it) }
                toggle(Icons.Outlined.Autorenew, "Keep the installed pack up to date", "Updates it when new apps get icons", autoUpdate) { s.setAutoUpdatePack(it) }
            }
            Group("App updates") {
                val status = when (val u = update) {
                    is MainViewModel.Update.Idle -> "Version $version"
                    is MainViewModel.Update.Checking -> "Checking…"
                    is MainViewModel.Update.UpToDate -> "Version $version · up to date"
                    is MainViewModel.Update.Available -> "Version ${u.release.version} is ready to install"
                    is MainViewModel.Update.Downloading -> "Downloading ${(u.progress * 100).toInt()}%"
                    is MainViewModel.Update.Failed -> u.message
                }
                val available = update as? MainViewModel.Update.Available
                item(Icons.Outlined.SystemUpdate, if (available != null) "Install update" else "Check for updates", status,
                    value = if (available != null) "Update" else null) {
                    when (val u = update) {
                        is MainViewModel.Update.Available -> vm.installUpdate(u.release)
                        is MainViewModel.Update.Checking, is MainViewModel.Update.Downloading -> {}
                        else -> vm.checkForUpdate(manual = true)
                    }
                }
                toggle(Icons.Outlined.Update, "Check automatically", "When you open the app, with a notification for new versions", autoCheck) { s.setAutoUpdateCheck(it) }
            }
            (update as? MainViewModel.Update.Downloading)?.let {
                LinearProgressIndicator(progress = { it.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            Group("About") {
                item(Icons.Outlined.Info, "Icon Mixer", "Version $version") {}
                item(Icons.Outlined.School, "Show the intro again", "Three short pages on how Icon Mixer works") { s.setTourDone(false) }
                item(Icons.Outlined.Code, "Source code", "github.com/${Updater.REPO}",
                    trailing = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }) {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.PAGE)))
                }
            }
        }
    }

    if (themeDialog) ChoiceDialog(
        "Theme", listOf(Settings.MODE_SYSTEM to "Follow system", Settings.MODE_LIGHT to "Light", Settings.MODE_DARK to "Dark"),
        selected = mode, onDismiss = { themeDialog = false },
    ) { s.setThemeMode(it); themeDialog = false }

    if (colourDialog) AlertDialog(
        onDismissRequest = { colourDialog = false },
        title = { Text("Colour") },
        text = {
            ColourPicker(wallpaper = wallpaperColours, seed = seed, swatch = 40,
                onWallpaper = { s.setDynamicColor(true) }, onColour = { s.setSeedColor(it) })
        },
        confirmButton = { TextButton(onClick = { colourDialog = false }) { Text("Done") } },
    )

    if (statsDialog) AlertDialog(
        onDismissRequest = { statsDialog = false },
        title = { Text("Statistics") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${apps.size} apps on the home screen (${apps.count { it.isSystem }} system)")
                Text("${packs.size} icon packs · ${packs.sumOf { it.totalIcons }} icons")
                Text("${themes.size} themes")
                themes.forEach { t -> Text("· ${t.name}: ${t.themedApps}/${t.totalApps} (${(t.coverage * 100).toInt()}%)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = { TextButton(onClick = { statsDialog = false }) { Text("Close") } },
    )
}

private fun colourName(seed: Int) = APP_COLOURS.firstOrNull { it.second == seed }?.first ?: "Custom"

// ------------------------------------------------------------------ building blocks

/** Collects a section's rows so each can get its place-dependent rounded corners. */
private class GroupScope {
    val rows = ArrayList<@Composable (RoundedCornerShape) -> Unit>()

    fun item(icon: ImageVector, title: String, hint: String?, value: String? = null,
             trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
        rows += { shape ->
            SettingRow(icon, title, hint, shape, onClick) {
                when {
                    trailing != null -> trailing()
                    value != null -> {
                        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Chevron()
                    }
                    else -> Chevron()
                }
            }
        }
    }

    fun toggle(icon: ImageVector, title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        rows += { shape -> SettingRow(icon, title, hint, shape, { onChange(!checked) }) { Switch(checked, onChange) } }
    }
}

@Composable
private fun Group(title: String, build: GroupScope.() -> Unit) {
    val g = GroupScope().apply(build)
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val big = 22.dp; val small = 6.dp
        g.rows.forEachIndexed { i, row ->
            val first = i == 0; val last = i == g.rows.lastIndex
            row(RoundedCornerShape(
                topStart = if (first) big else small, topEnd = if (first) big else small,
                bottomStart = if (last) big else small, bottomEnd = if (last) big else small,
            ))
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector, title: String, hint: String?, shape: RoundedCornerShape, onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = shape, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).background(cs.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = cs.onSecondaryContainer, modifier = Modifier.size(20.dp))
            }
            Gap(w = 14.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            }
            Gap(w = 8.dp)
            Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
        }
    }
}

@Composable
private fun Chevron() = Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun ColourDot(c: Color) = Box(Modifier.padding(end = 4.dp).size(20.dp).background(c, CircleShape))

@Composable
private fun ChoiceDialog(title: String, options: List<Pair<Int, String>>, selected: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (v, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = v == selected, onClick = { onPick(v) }).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = v == selected, onClick = { onPick(v) })
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

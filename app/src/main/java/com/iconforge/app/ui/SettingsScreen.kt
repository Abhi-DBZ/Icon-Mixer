package com.iconforge.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.AppShortcut
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.iconforge.app.core.Updater
import com.iconforge.app.data.Settings

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
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Appearance
            Section(Icons.Outlined.Palette, "Appearance") {
                val modes = listOf(
                    Triple(Settings.MODE_SYSTEM, "Auto", Icons.Outlined.BrightnessAuto),
                    Triple(Settings.MODE_LIGHT, "Light", Icons.Outlined.LightMode),
                    Triple(Settings.MODE_DARK, "Dark", Icons.Outlined.DarkMode),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    modes.forEachIndexed { i, (m, label, icon) ->
                        SegmentedButton(
                            selected = mode == m, onClick = { s.setThemeMode(m) },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            icon = { Icon(icon, null, Modifier.size(18.dp)) },
                        ) { Text(label) }
                    }
                }
                Text("Colour", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 8.dp))
                ColourPicker(
                    wallpaper = dynamic && Build.VERSION.SDK_INT >= 31, seed = seed, swatch = 36,
                    onWallpaper = { s.setDynamicColor(true) }, onColour = { s.setSeedColor(it) },
                )
                Setting(Icons.Outlined.Contrast, "Pure black dark theme", "For AMOLED screens", black) { s.setPureBlack(it) }
            }

            // ---- New apps
            Section(Icons.Outlined.NotificationsActive, "New apps") {
                Setting(Icons.Outlined.NotificationsActive, "Notify about unthemed apps",
                    "When a new app has no icon in your theme", notify) { s.setNotifyNewApps(it) }
                Setting(Icons.Outlined.Autorenew, "Keep the installed pack up to date",
                    "Updates it when new apps get icons", autoUpdate) { s.setAutoUpdatePack(it) }
            }

            // ---- Updates
            Section(Icons.Outlined.SystemUpdate, "App updates") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Icon Mixer $version", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when (val u = update) {
                                is MainViewModel.Update.Idle -> "Updates come from GitHub"
                                is MainViewModel.Update.Checking -> "Checking…"
                                is MainViewModel.Update.UpToDate -> "You have the latest version"
                                is MainViewModel.Update.Available -> "Version ${u.release.version} is available"
                                is MainViewModel.Update.Downloading -> "Downloading ${(u.progress * 100).toInt()}%"
                                is MainViewModel.Update.Failed -> u.message
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (update is MainViewModel.Update.Available) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when (val u = update) {
                        is MainViewModel.Update.Available -> Button(onClick = { vm.installUpdate(u.release) }) { Text("Update") }
                        is MainViewModel.Update.Checking, is MainViewModel.Update.Downloading -> {}
                        else -> TextButton(onClick = { vm.checkForUpdate(manual = true) }) { Text("Check") }
                    }
                }
                (update as? MainViewModel.Update.Downloading)?.let {
                    LinearProgressIndicator(progress = { it.progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                }
                (update as? MainViewModel.Update.Available)?.release?.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                    Text(notes.lines().filter { it.isNotBlank() && !it.startsWith("🤖") }.take(6).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
                Setting(Icons.Outlined.Update, "Check automatically", "Once a day, when you open the app", autoCheck) { s.setAutoUpdateCheck(it) }
            }

            // ---- About
            Section(Icons.Outlined.Info, "About") {
                Link(Icons.AutoMirrored.Outlined.OpenInNew, "Source code on GitHub", Updater.PAGE) {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.PAGE)))
                }
                Link(Icons.Outlined.AppShortcut, "Exported packs",
                    "Signed with a key kept on this phone. After reinstalling Icon Mixer, remove old packs before installing new ones.") {}
            }
        }
    }

}


/** A titled card with an icon. */
@Composable
private fun Section(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Gap(w = 10.dp)
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

/** Icon + title + one-line hint + switch; the whole row toggles. */
@Composable
private fun Setting(icon: ImageVector, title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Gap(w = 14.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Gap(w = 8.dp)
        Switch(checked, onChange)
    }
}

@Composable
private fun Link(icon: ImageVector, title: String, hint: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Gap(w = 14.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

package com.iconforge.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.iconforge.app.export.InstallEvents
import kotlinx.coroutines.flow.collectLatest
import com.iconforge.app.ui.AppsScreen
import com.iconforge.app.ui.EditorScreen
import com.iconforge.app.ui.ExportScreen
import com.iconforge.app.ui.HomeScreen
import com.iconforge.app.ui.IconForgeTheme
import com.iconforge.app.ui.MainViewModel
import com.iconforge.app.ui.PacksScreen
import com.iconforge.app.ui.PickerScreen
import com.iconforge.app.ui.SettingsScreen
import androidx.compose.material3.Scaffold

/** Routes. Component keys contain '/', so they are Uri-encoded. */
object Routes {
    const val HOME = "home"
    const val APPS = "apps"
    const val PACKS = "packs"
    const val SETTINGS = "settings"
    fun editor(themeId: Long, filter: String = "ALL") = "editor/$themeId?filter=$filter"
    fun picker(themeId: Long, keys: Collection<String>) = "picker/$themeId?keys=" + Uri.encode(keys.joinToString("|"))
    fun export(themeId: Long) = "export/$themeId"
    fun preview(themeId: Long) = "preview/$themeId"
}

class MainActivity : ComponentActivity() {
    private val pendingIntent = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingIntent.value = intent
        setContent {
            IconForgeTheme(app.settings) {
                val tourDone by app.settings.tourDone.collectAsState()
                androidx.compose.foundation.layout.Box {
                    Root(pendingIntent.value) { pendingIntent.value = null }
                    if (!tourDone) com.iconforge.app.ui.TourScreen { app.settings.setTourDone(true) }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); AppState.foreground = true }
    override fun onStop() { AppState.foreground = false; super.onStop() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingIntent.value = intent
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Themes", Icons.Outlined.Home),
    Tab(Routes.APPS, "Apps", Icons.Outlined.Apps),
    Tab(Routes.PACKS, "Icon packs", Icons.Outlined.Palette),
    Tab(Routes.SETTINGS, "Settings", Icons.Outlined.Settings),
)

@Composable
private fun Root(launchIntent: Intent?, consumed: () -> Unit) {
    val nav = rememberNavController()
    val vm: MainViewModel = viewModel()
    val snackbar = remember { SnackbarHostState() }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(Unit) {
        vm.messages.collect { m ->
            val r = snackbar.showSnackbar(
                m.text, actionLabel = m.action, withDismissAction = m.action != null,
                duration = if (m.action != null) androidx.compose.material3.SnackbarDuration.Long else androidx.compose.material3.SnackbarDuration.Short,
            )
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) m.onAction?.invoke()
        }
    }
    LaunchedEffect(Unit) { InstallEvents.events.collect { snackbar.showSnackbar(it) } }
    // Icon changed / removed: a short snackbar with Undo (a newer change replaces the old one).
    LaunchedEffect(Unit) {
        vm.undoable.collectLatest { text ->
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(text, actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Short)
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.undo()
        }
    }
    // Packs or apps may have been installed while we were away.
    var first by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (!first) { vm.refreshQuietly(); vm.checkOnOpen() }
        first = false
        onPauseOrDispose { }
    }

    // Deep links from the "new app" notification.
    LaunchedEffect(launchIntent) {
        val i = launchIntent ?: return@LaunchedEffect
        // A theme file shared from / opened in another app: import it.
        val shared: Uri? = when (i.action) {
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_VIEW -> i.data
            else -> null
        }
        if (shared != null) { vm.import(shared) { nav.switchTab(Routes.HOME) }; consumed(); return@LaunchedEffect }
        val theme = i.getLongExtra(Notifications.EXTRA_THEME, 0L)
        when (i.getStringExtra(Notifications.EXTRA_OPEN)) {
            "picker" -> i.getStringExtra(Notifications.EXTRA_COMPONENT)?.let { nav.navigate(Routes.picker(theme, listOf(it))) }
            "new" -> nav.navigate(Routes.editor(theme, "NEW"))
            "update" -> vm.checkOnOpen(force = true)        // the banner on Home has the Update button
        }
        consumed()
    }

    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = tabs.any { it.route == route }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showBar) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = { nav.switchTab(t.route) },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { pad ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(pad)) {
            composable(Routes.HOME) { HomeScreen(vm, nav) }
            composable(Routes.APPS) { AppsScreen(vm, nav) }
            composable(Routes.PACKS) { PacksScreen(vm, nav) }
            composable(Routes.SETTINGS) { SettingsScreen(vm) }
            composable("editor/{themeId}?filter={filter}") { e ->
                val id = e.arguments?.getString("themeId")?.toLongOrNull() ?: return@composable
                EditorScreen(vm, nav, id, e.arguments?.getString("filter") ?: "ALL")
            }
            composable("picker/{themeId}?keys={keys}") { e ->
                val id = e.arguments?.getString("themeId")?.toLongOrNull() ?: return@composable
                val keys = (e.arguments?.getString("keys") ?: "").split('|').filter { it.isNotBlank() }
                PickerScreen(vm, nav, id, keys)
            }
            composable("preview/{themeId}") { e ->
                val id = e.arguments?.getString("themeId")?.toLongOrNull() ?: return@composable
                com.iconforge.app.ui.PreviewScreen(vm, nav, id)
            }
            composable("export/{themeId}") { e ->
                val id = e.arguments?.getString("themeId")?.toLongOrNull() ?: return@composable
                ExportScreen(nav, id)
            }
        }
    }
}

fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(Routes.HOME) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

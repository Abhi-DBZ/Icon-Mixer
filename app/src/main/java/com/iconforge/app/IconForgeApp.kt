package com.iconforge.app

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.iconforge.app.core.AppScanner
import com.iconforge.app.core.IconLoader
import com.iconforge.app.core.IconPacks
import com.iconforge.app.core.ThemeRepository
import com.iconforge.app.data.AppDatabase
import com.iconforge.app.data.InstalledApp
import com.iconforge.app.data.Settings
import com.iconforge.app.export.PackExporter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class IconForgeApp : Application() {
    lateinit var db: AppDatabase; private set
    lateinit var settings: Settings; private set
    lateinit var packs: IconPacks; private set
    lateinit var scanner: AppScanner; private set
    lateinit var icons: IconLoader; private set
    lateinit var repo: ThemeRepository; private set
    lateinit var exporter: PackExporter; private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pendingSync: Job? = null

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.create(this)
        settings = Settings(this)
        packs = IconPacks(this)
        scanner = AppScanner(this)
        icons = IconLoader(this, packs, settings)
        repo = ThemeRepository(this, db, packs, scanner, settings)
        exporter = PackExporter(this, db, packs, icons, repo, settings)

        Notifications.createChannel(this)
        NewAppWorker.schedule(this)
        UpdateCheckWorker.schedule(this)
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { icons.trimDiskCache() } }

        // While the app process is alive, react to installs immediately.
        getSystemService(LauncherApps::class.java).registerCallback(object : LauncherApps.Callback() {
            override fun onPackageAdded(packageName: String, user: UserHandle) { icons.forget(packageName); changed(user) }
            override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(user)
            override fun onPackageChanged(packageName: String, user: UserHandle) { icons.forget(packageName); changed(user) }
            override fun onPackagesAvailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = changed(user)
            override fun onPackagesUnavailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = changed(user)
        })
    }

    private fun changed(user: UserHandle) {
        if (user != Process.myUserHandle()) return
        pendingSync?.cancel()
        pendingSync = scope.launch {
            delay(1500) // installs fire several callbacks; sync once
            syncNow(notify = true)
        }
    }

    /** Re-scan apps and icon packs; notify about new unthemed apps. */
    suspend fun syncNow(notify: Boolean) {
        runCatching { repo.syncPacks() }
        val r = repo.syncApps()
        if (notify && settings.notifyNewApps.value && r.stillMissing.isNotEmpty()) {
            Notifications.newApps(this, r.stillMissing, settings.activeTheme.value)
        }
        // New apps got icons: refresh the pack that's on the home screen.
        if (r.activeThemedNew > 0 && settings.autoUpdatePack.value) {
            val theme = db.themes().get(settings.activeTheme.value)
            if (theme != null && exporter.isInstalled(theme)) PackUpdateWorker.enqueue(this, theme.id)
        }
    }
}

/** Whether an activity is visible (installers may only start screens from the foreground). */
object AppState {
    @Volatile var foreground = false
}

/** Rebuilds the theme's pack and installs it as a silent update (Android 12+). */
class PackUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        val id = inputData.getLong("theme", 0L)
        return try {
            val apk = app.exporter.buildApk(id) { _, _ -> }
            app.exporter.install(apk, silent = true)
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    companion object {
        fun enqueue(ctx: Context, themeId: Long) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "pack-update", androidx.work.ExistingWorkPolicy.REPLACE,
                androidx.work.OneTimeWorkRequestBuilder<PackUpdateWorker>()
                    .setInitialDelay(5, TimeUnit.SECONDS)
                    .setInputData(androidx.work.workDataOf("theme" to themeId))
                    .build(),
            )
        }
    }
}

val Context.app: IconForgeApp get() = applicationContext as IconForgeApp

/**
 * Periodic fallback for detecting installs while the app isn't running (Android no
 * longer delivers PACKAGE_ADDED broadcasts to manifest receivers). Cheap when nothing changed.
 */
class NewAppWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        val seq = app.settings.changedPackagesSeq
        val changes = applicationContext.packageManager.getChangedPackages(seq)
        if (changes == null && seq != 0) return Result.success()
        changes?.let { app.settings.changedPackagesSeq = it.sequenceNumber }
        app.syncNow(notify = true)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "new-apps",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<NewAppWorker>(30, TimeUnit.MINUTES).build(),
            )
        }
    }
}

/**
 * Looks for a new Icon Mixer release on GitHub every few hours (with a network) and posts one
 * notification per new version. Off with Settings → App updates → check automatically.
 */
class UpdateCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        if (!app.settings.autoUpdateCheck.value) return Result.success()
        val r = runCatching { com.iconforge.app.core.Updater.latest() }.getOrNull() ?: return Result.success()
        val local = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: return Result.success()
        if (com.iconforge.app.core.Updater.isNewer(r.version, local) && app.settings.notifiedUpdate != r.version) {
            Notifications.appUpdate(app, r.version)
            app.settings.notifiedUpdate = r.version
        }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "update-check",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
                    .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}

object Notifications {
    private const val CHANNEL = "new_apps"
    private const val CHANNEL_PACK = "pack_updates"
    private const val CHANNEL_APP = "app_updates"

    /** A new Icon Mixer version is on GitHub; tapping opens the app, where Home offers Update. */
    fun appUpdate(ctx: Context, version: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val tap = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN, "update")
        }
        val pi = PendingIntent.getActivity(ctx, 9, tap, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL_APP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Icon Mixer $version is available")
            .setContentText("Tap to open Icon Mixer and update")
            .setContentIntent(pi).setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(1003, n) }
    }
    const val EXTRA_OPEN = "open"
    const val EXTRA_THEME = "theme"
    const val EXTRA_COMPONENT = "component"

    fun createChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, "New apps needing icons", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "When you install an app your theme has no icon for" }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_PACK, "Icon pack updates", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "When your installed icon pack is updated with new icons" },
        )
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_APP, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "When a new version of Icon Mixer is available" },
        )
    }

    /** [action] (optional) runs when tapped, e.g. the installer's confirm screen. */
    fun packUpdate(ctx: Context, title: String, text: String, action: Intent?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val tap = action ?: Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, 8, tap, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL_PACK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(pi).setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(1002, n) }
    }

    fun newApps(ctx: Context, apps: List<InstalledApp>, themeId: Long) {
        if (themeId == 0L) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val single = apps.size == 1
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_THEME, themeId)
            if (single) { putExtra(EXTRA_OPEN, "picker"); putExtra(EXTRA_COMPONENT, apps[0].componentKey) }
            else putExtra(EXTRA_OPEN, "new")
        }
        val pi = PendingIntent.getActivity(ctx, 7, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = if (single) "${apps[0].label} needs an icon" else "${apps.size} new apps need icons"
        val text = if (single) "Your icon pack has no icon for it. Tap to pick one."
        else apps.take(4).joinToString(", ") { it.label } + if (apps.size > 4) "…" else ""
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(1001, n) }
    }
}

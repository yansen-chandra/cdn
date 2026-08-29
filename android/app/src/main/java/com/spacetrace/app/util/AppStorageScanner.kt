package com.spacetrace.app.util

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import com.spacetrace.app.model.AppStorageEntry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Per-app storage totals via StorageStatsManager -- an app's private data directory is sandboxed
 * from every other app (including this one) at the OS level, so there's no way to browse it.
 * This is the same aggregate-bytes-only API Settings > Storage itself uses for that breakdown.
 */
object AppStorageScanner {

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** Every installed app with its total storage, largest first. Skips any package that fails. */
    suspend fun scan(context: Context): List<AppStorageEntry> {
        val pm = context.packageManager
        val statsManager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val ctx = currentCoroutineContext()

        val entries = ArrayList<AppStorageEntry>(installedApps.size)
        for (app in installedApps) {
            ctx.ensureActive()
            try {
                val stats = statsManager.queryStatsForPackage(
                    StorageManager.UUID_DEFAULT,
                    app.packageName,
                    Process.myUserHandle(),
                )
                val icon = try {
                    pm.getApplicationIcon(app)
                } catch (_: Exception) {
                    null
                }
                entries.add(
                    AppStorageEntry(
                        packageName = app.packageName,
                        appName = pm.getApplicationLabel(app).toString(),
                        appBytes = stats.appBytes,
                        dataBytes = stats.dataBytes,
                        cacheBytes = stats.cacheBytes,
                        isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        icon = icon,
                    )
                )
            } catch (_: Exception) {
                // A handful of stub/instant-app packages throw NameNotFoundException or
                // IOException here even when otherwise listed; skip rather than fail the scan.
            }
        }
        return entries.sortedByDescending { it.totalBytes }
    }
}

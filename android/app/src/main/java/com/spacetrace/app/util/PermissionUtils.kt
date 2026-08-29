package com.spacetrace.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

object PermissionUtils {

    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    /** Deep-links to this app's "All files access" toggle in system Settings. */
    fun allFilesAccessSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /** Fallback if the device doesn't resolve the per-app screen. */
    fun allFilesAccessFallbackIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
}

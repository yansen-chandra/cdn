package com.spacetrace.app.model

import android.graphics.drawable.Drawable

/** Aggregate storage used by one installed app, from StorageStatsManager -- never raw file access. */
data class AppStorageEntry(
    val packageName: String,
    val appName: String,
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
    val isSystemApp: Boolean,
    val icon: Drawable?,
) {
    // dataBytes already includes cacheBytes, so the total is just code + data.
    val totalBytes: Long get() = appBytes + dataBytes
}

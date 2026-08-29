package com.spacetrace.app.model

/** Aggregated size/count for a directory, including everything nested under it. */
data class FolderStat(
    val path: String,
    val name: String,
    val totalSizeBytes: Long,
    val fileCount: Int,
)

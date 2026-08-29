package com.spacetrace.app.model

/** Immutable snapshot produced by a completed (or in-progress) scan. */
data class ScanResult(
    val entries: List<FileEntry> = emptyList(),
    val folderSizeByPath: Map<String, Long> = emptyMap(),
    val folderCountByPath: Map<String, Int> = emptyMap(),
    val rootPaths: List<String> = emptyList(),
    val totalBytes: Long = 0L,
    val totalFileCount: Int = 0,
    val scannedAtMillis: Long = 0L,
) {
    val files: List<FileEntry> get() = entries.filter { !it.isDirectory }
}

data class ScanProgress(
    val isScanning: Boolean = false,
    val filesScanned: Int = 0,
    val currentPath: String = "",
    val bytesScanned: Long = 0L,
)

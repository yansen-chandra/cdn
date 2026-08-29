package com.spacetrace.app.model

/**
 * A single file or directory discovered during a scan.
 *
 * [createdAtMillis] is best-effort: most Android shared-storage filesystems
 * (FUSE-emulated /storage/emulated/0) do not preserve a true file "birth
 * time", so this often falls back to the last-modified time. See
 * [com.spacetrace.app.scan.FileScanner] for details.
 */
data class FileEntry(
    val path: String,
    val name: String,
    val parentPath: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val isHidden: Boolean,
    val createdAtMillis: Long,
    val modifiedAtMillis: Long,
) {
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "")
}

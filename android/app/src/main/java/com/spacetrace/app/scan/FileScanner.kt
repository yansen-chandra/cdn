package com.spacetrace.app.scan

import android.content.Context
import android.os.storage.StorageManager
import com.spacetrace.app.model.FileEntry
import com.spacetrace.app.model.ScanProgress
import com.spacetrace.app.model.ScanResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * Walks the device's shared-storage volumes with plain [java.io.File] APIs
 * (not MediaStore), so dot-prefixed "hidden" files/directories and files of
 * every type are included -- MediaStore deliberately excludes both.
 *
 * Requires MANAGE_EXTERNAL_STORAGE ("All files access") to see anything
 * beyond the app's own sandbox.
 */
object FileScanner {

    /** Returns every storage volume root this app can attempt to read (internal + SD/USB). */
    fun findStorageRoots(context: Context): List<File> {
        val storageManager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return storageManager.storageVolumes
            .mapNotNull { volume -> volume.directory }
            .filter { it.exists() && it.canRead() }
            .distinctBy { it.absolutePath }
    }

    /**
     * Performs a full iterative depth-first walk of [roots].
     *
     * @param includeHidden when false, dot-prefixed files/directories (and everything under
     *   a hidden directory) are skipped entirely.
     * @param onProgress invoked periodically (not for every single file) with a running tally.
     */
    suspend fun scan(
        roots: List<File>,
        includeHidden: Boolean = true,
        onProgress: suspend (ScanProgress) -> Unit = {},
    ): ScanResult {
        val entries = ArrayList<FileEntry>(4096)
        val folderSize = HashMap<String, Long>(1024)
        val folderCount = HashMap<String, Int>(1024)

        var filesScanned = 0
        var bytesScanned = 0L
        val stack = ArrayDeque<File>()

        for (root in roots) {
            folderSize.putIfAbsent(root.absolutePath, 0L)
            folderCount.putIfAbsent(root.absolutePath, 0)
            stack.addLast(root)
        }

        val ctx = currentCoroutineContext()
        var sinceYield = 0

        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = safeListFiles(dir) ?: continue

            for (child in children) {
                val hidden = child.isHidden || child.name.startsWith(".")
                if (!includeHidden && hidden) continue

                val isDir = child.isDirectory
                val size = if (isDir) 0L else safeLength(child)
                val (created, modified) = safeTimestamps(child)

                entries.add(
                    FileEntry(
                        path = child.absolutePath,
                        name = child.name,
                        parentPath = dir.absolutePath,
                        sizeBytes = size,
                        isDirectory = isDir,
                        isHidden = hidden,
                        createdAtMillis = created,
                        modifiedAtMillis = modified,
                    )
                )

                if (isDir) {
                    folderSize.putIfAbsent(child.absolutePath, 0L)
                    folderCount.putIfAbsent(child.absolutePath, 0)
                    stack.addLast(child)
                } else {
                    filesScanned++
                    bytesScanned += size
                    // Attribute this file's size/count to every ancestor directory,
                    // from its immediate parent up to (and including) the scan root.
                    var ancestor: File? = dir
                    while (ancestor != null) {
                        val ap = ancestor.absolutePath
                        folderSize[ap] = (folderSize[ap] ?: 0L) + size
                        folderCount[ap] = (folderCount[ap] ?: 0) + 1
                        if (roots.any { it.absolutePath == ap }) break
                        ancestor = ancestor.parentFile
                    }
                }

                sinceYield++
                if (sinceYield >= 250) {
                    sinceYield = 0
                    ctx.ensureActive()
                    yield()
                    onProgress(
                        ScanProgress(
                            isScanning = true,
                            filesScanned = filesScanned,
                            currentPath = child.absolutePath,
                            bytesScanned = bytesScanned,
                        )
                    )
                }
            }
        }

        // Back-fill directory entries with their aggregated size now that the walk is complete.
        val finalEntries = entries.map { e ->
            if (e.isDirectory) e.copy(sizeBytes = folderSize[e.path] ?: 0L) else e
        }

        onProgress(ScanProgress(isScanning = false, filesScanned = filesScanned, bytesScanned = bytesScanned))

        return ScanResult(
            entries = finalEntries,
            folderSizeByPath = folderSize,
            folderCountByPath = folderCount,
            rootPaths = roots.map { it.absolutePath },
            totalBytes = bytesScanned,
            totalFileCount = filesScanned,
            scannedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun safeListFiles(dir: File): Array<File>? =
        try {
            dir.listFiles()
        } catch (_: SecurityException) {
            null
        }

    private fun safeLength(file: File): Long =
        try {
            file.length()
        } catch (_: SecurityException) {
            0L
        }

    /** Returns (createdAtMillis, modifiedAtMillis). Falls back to lastModified() on any failure. */
    private fun safeTimestamps(file: File): Pair<Long, Long> {
        val modified = try {
            file.lastModified()
        } catch (_: SecurityException) {
            0L
        }
        val created = try {
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            val creationMillis = attrs.creationTime().toMillis()
            // Many Android filesystems don't track a real birth time and report either
            // epoch 0 or the same value as mtime; treat a clearly-bogus value as "unknown".
            if (creationMillis > 0L) creationMillis else modified
        } catch (_: Exception) {
            modified
        }
        return created to modified
    }
}

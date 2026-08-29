package com.spacetrace.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spacetrace.app.model.FileEntry
import com.spacetrace.app.model.ScanProgress
import com.spacetrace.app.model.ScanResult
import com.spacetrace.app.scan.FileScanner
import com.spacetrace.app.util.PermissionUtils
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val _hasPermission = MutableStateFlow(PermissionUtils.hasAllFilesAccess())
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private val _progress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = _progress.asStateFlow()

    private val _result = MutableStateFlow(ScanResult())
    val result: StateFlow<ScanResult> = _result.asStateFlow()

    private val _showHidden = MutableStateFlow(true)
    val showHidden: StateFlow<Boolean> = _showHidden.asStateFlow()

    private var scanJob: Job? = null

    fun refreshPermissionState() {
        _hasPermission.value = PermissionUtils.hasAllFilesAccess()
    }

    /**
     * Purely a display filter for the Recent/Browse lists -- the scan itself always walks
     * hidden files so the Storage tab's space totals stay accurate regardless of this toggle.
     */
    fun toggleShowHidden() {
        _showHidden.value = !_showHidden.value
    }

    fun startScan(force: Boolean = false) {
        if (!PermissionUtils.hasAllFilesAccess()) {
            _hasPermission.value = false
            return
        }
        _hasPermission.value = true
        if (scanJob?.isActive == true && !force) return
        scanJob?.cancel()

        scanJob = viewModelScope.launch {
            _progress.value = ScanProgress(isScanning = true)
            val roots = FileScanner.findStorageRoots(getApplication())
            val scanned = FileScanner.scan(
                roots = roots,
                includeHidden = true,
                onProgress = { _progress.value = it },
            )
            _result.value = scanned
            _progress.value = ScanProgress(isScanning = false, filesScanned = scanned.totalFileCount)
        }
    }

    /** Deletes a file (not a directory) from disk and removes it from the in-memory result. */
    fun deleteFile(entry: FileEntry): Boolean {
        if (entry.isDirectory) return false
        val deleted = try {
            File(entry.path).delete()
        } catch (_: SecurityException) {
            false
        }
        if (deleted) {
            val current = _result.value
            val remainingEntries = current.entries.filterNot { it.path == entry.path }
            val newFolderSize = current.folderSizeByPath.toMutableMap()
            val newFolderCount = current.folderCountByPath.toMutableMap()
            var ancestor: File? = File(entry.parentPath)
            while (ancestor != null) {
                val ap = ancestor.absolutePath
                if (newFolderSize.containsKey(ap)) {
                    newFolderSize[ap] = (newFolderSize[ap] ?: 0L) - entry.sizeBytes
                    newFolderCount[ap] = (newFolderCount[ap] ?: 1) - 1
                }
                if (current.rootPaths.contains(ap)) break
                ancestor = ancestor.parentFile
            }
            _result.value = current.copy(
                entries = remainingEntries,
                folderSizeByPath = newFolderSize,
                folderCountByPath = newFolderCount,
                totalBytes = current.totalBytes - entry.sizeBytes,
                totalFileCount = current.totalFileCount - 1,
            )
        }
        return deleted
    }
}

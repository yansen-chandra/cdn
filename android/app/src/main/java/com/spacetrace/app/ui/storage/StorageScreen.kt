package com.spacetrace.app.ui.storage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spacetrace.app.model.FileEntry
import com.spacetrace.app.model.FolderStat
import com.spacetrace.app.model.ScanResult
import com.spacetrace.app.ui.common.DeleteConfirmDialog
import com.spacetrace.app.ui.common.FileRow
import com.spacetrace.app.ui.common.SizeBar
import com.spacetrace.app.util.FormatUtils

private enum class StorageTab { FOLDERS, FILES }

@Composable
fun StorageScreen(
    result: ScanResult,
    showHidden: Boolean,
    onDelete: (FileEntry) -> Unit,
) {
    var tab by remember { mutableStateOf(StorageTab.FOLDERS) }
    var pendingDelete by remember { mutableStateOf<FileEntry?>(null) }

    val topFolders = remember(result) {
        // Exclude the storage-volume roots themselves -- their total is already the
        // header total above, so listing them as "biggest folders" is redundant.
        result.folderSizeByPath.entries
            .filterNot { result.rootPaths.contains(it.key) }
            .sortedByDescending { it.value }
            .take(50)
            .map { (path, size) ->
                FolderStat(
                    path = path,
                    name = path.substringAfterLast('/'),
                    totalSizeBytes = size,
                    fileCount = result.folderCountByPath[path] ?: 0,
                )
            }
    }

    val topFiles = remember(result, showHidden) {
        val files = if (showHidden) result.files else result.files.filterNot { it.isHidden }
        files.sortedByDescending { it.sizeBytes }.take(100)
    }

    val maxBytes = (topFolders.firstOrNull()?.totalSizeBytes ?: topFiles.firstOrNull()?.sizeBytes ?: 1L)
        .coerceAtLeast(1L)

    Column(Modifier.fillMaxSize()) {
        SummaryHeader(result)
        TabRow(tab, onSelect = { tab = it })
        HorizontalDivider()
        when (tab) {
            StorageTab.FOLDERS -> LazyColumn(Modifier.fillMaxSize()) {
                items(topFolders, key = { it.path }) { folder ->
                    FolderRow(folder, fraction = folder.totalSizeBytes.toFloat() / maxBytes)
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
            StorageTab.FILES -> LazyColumn(Modifier.fillMaxSize()) {
                items(topFiles, key = { it.path }) { entry ->
                    FileRow(
                        entry = entry,
                        subtitle = entry.parentPath,
                        onLongClick = { pendingDelete = entry },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        DeleteConfirmDialog(
            entry = entry,
            onConfirm = {
                onDelete(entry)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun SummaryHeader(result: ScanResult) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(FormatUtils.formatBytes(result.totalBytes), style = MaterialTheme.typography.headlineMedium)
        Text(
            "${FormatUtils.formatCount(result.totalFileCount)} files scanned",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TabRow(selected: StorageTab, onSelect: (StorageTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        StorageTab.values().forEach { t ->
            val label = if (t == StorageTab.FOLDERS) "Biggest folders" else "Biggest files"
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (t == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(end = 24.dp)
                    .clickable { onSelect(t) },
            )
        }
    }
}

@Composable
private fun FolderRow(folder: FolderStat, fraction: Float) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    folder.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(FormatUtils.formatBytes(folder.totalSizeBytes), style = MaterialTheme.typography.labelLarge)
            }
            Text(
                folder.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(4.dp))
            SizeBar(fraction)
        }
    }
}

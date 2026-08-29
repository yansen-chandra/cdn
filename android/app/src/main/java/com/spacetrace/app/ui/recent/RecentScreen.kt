package com.spacetrace.app.ui.recent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spacetrace.app.model.FileEntry
import com.spacetrace.app.model.ScanProgress
import com.spacetrace.app.model.ScanResult
import com.spacetrace.app.ui.common.DeleteConfirmDialog
import com.spacetrace.app.ui.common.FileRow
import com.spacetrace.app.util.DateGrouping
import com.spacetrace.app.util.FormatUtils
import java.text.SimpleDateFormat
import java.util.Locale

private enum class RecentViewMode { DATE, TREE }

private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

@Composable
fun RecentScreen(
    result: ScanResult,
    progress: ScanProgress,
    showHidden: Boolean,
    onDelete: (FileEntry) -> Unit,
) {
    var viewMode by remember { mutableStateOf(RecentViewMode.DATE) }
    var pendingDelete by remember { mutableStateOf<FileEntry?>(null) }

    val files = remember(result, showHidden) {
        if (showHidden) result.files else result.files.filterNot { it.isHidden }
    }

    Column(Modifier.fillMaxSize()) {
        if (progress.isScanning) {
            ScanBanner(progress)
        }
        ViewModeRow(viewMode, onSelect = { viewMode = it })
        HorizontalDivider()

        when (viewMode) {
            RecentViewMode.DATE -> DateGroupedList(
                files = files,
                isScanning = progress.isScanning,
                onFileLongClick = { pendingDelete = it },
            )
            RecentViewMode.TREE -> RecentTreeList(
                files = files,
                roots = result.rootPaths,
                onFileLongClick = { pendingDelete = it },
            )
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
private fun DateGroupedList(
    files: List<FileEntry>,
    isScanning: Boolean,
    onFileLongClick: (FileEntry) -> Unit,
) {
    val groups = remember(files) { DateGrouping.groupByCreatedDay(files) }

    if (groups.isEmpty() && !isScanning) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No files found yet. Pull to scan.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        groups.forEach { group ->
            item(key = "header_${group.dayKey}") {
                DayHeader(group.label, group.files.sumOf { it.sizeBytes })
            }
            items(group.files, key = { it.path }) { entry ->
                FileRow(
                    entry = entry,
                    subtitle = timeFormat.format(entry.createdAtMillis),
                    onLongClick = { onFileLongClick(entry) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@Composable
private fun ViewModeRow(selected: RecentViewMode, onSelect: (RecentViewMode) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        val labels = mapOf(RecentViewMode.DATE to "By date", RecentViewMode.TREE to "By tree")
        labels.forEach { (mode, label) ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (mode == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(end = 24.dp)
                    .clickable { onSelect(mode) },
            )
        }
    }
}

@Composable
private fun DayHeader(label: String, totalBytes: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(
            FormatUtils.formatBytes(totalBytes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScanBanner(progress: ScanProgress) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Scanning... ${FormatUtils.formatCount(progress.filesScanned)} files",
            style = MaterialTheme.typography.bodySmall,
        )
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
    }
}

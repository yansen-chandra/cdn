package com.spacetrace.app.ui.recent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
    var revealPath by remember { mutableStateOf<String?>(null) }

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
                onFileClick = { entry ->
                    revealPath = entry.path
                    viewMode = RecentViewMode.TREE
                },
                onFileLongClick = { pendingDelete = it },
            )
            RecentViewMode.TREE -> RecentTreeList(
                files = files,
                roots = result.rootPaths,
                revealPath = revealPath,
                onRevealed = { revealPath = null },
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
    onFileClick: (FileEntry) -> Unit,
    onFileLongClick: (FileEntry) -> Unit,
) {
    val groups = remember(files) { DateGrouping.groupByCreatedDay(files) }
    // Every day section starts expanded (matches the old always-visible behavior); collapsing
    // one is opt-in per day rather than something that needs to survive a rescan.
    var expandedDays by remember(groups) { mutableStateOf(groups.map { it.dayKey }.toSet()) }

    if (groups.isEmpty() && !isScanning) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No files found yet. Pull to scan.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        groups.forEach { group ->
            val isExpanded = expandedDays.contains(group.dayKey)
            item(key = "header_${group.dayKey}") {
                DayHeader(
                    label = group.label,
                    totalBytes = group.files.sumOf { it.sizeBytes },
                    expanded = isExpanded,
                    onClick = {
                        expandedDays = if (isExpanded) {
                            expandedDays - group.dayKey
                        } else {
                            expandedDays + group.dayKey
                        }
                    },
                )
            }
            if (isExpanded) {
                items(group.files, key = { it.path }) { entry ->
                    FileRow(
                        entry = entry,
                        subtitle = timeFormat.format(entry.createdAtMillis),
                        path = entry.parentPath,
                        onClick = { onFileClick(entry) },
                        onLongClick = { onFileLongClick(entry) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
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
private fun DayHeader(label: String, totalBytes: Long, expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
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

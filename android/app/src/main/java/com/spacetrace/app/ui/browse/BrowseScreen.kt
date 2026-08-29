package com.spacetrace.app.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spacetrace.app.model.FileEntry
import com.spacetrace.app.model.ScanResult
import com.spacetrace.app.ui.common.DeleteConfirmDialog
import com.spacetrace.app.ui.common.FileRow
import com.spacetrace.app.util.DateGrouping

@Composable
fun BrowseScreen(
    result: ScanResult,
    showHidden: Boolean,
    onDelete: (FileEntry) -> Unit,
) {
    var currentPath by remember(result.rootPaths) {
        mutableStateOf(result.rootPaths.firstOrNull())
    }
    var pendingDelete by remember { mutableStateOf<FileEntry?>(null) }

    val children = remember(result, currentPath, showHidden) {
        if (currentPath == null) emptyList()
        else result.entries
            .filter { it.parentPath == currentPath }
            .filter { showHidden || !it.isHidden }
            .sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    BackHandler(enabled = currentPath != null && !result.rootPaths.contains(currentPath)) {
        currentPath = parentOf(currentPath, result.rootPaths)
    }

    Column(Modifier.fillMaxSize()) {
        PathBar(
            path = currentPath,
            canGoUp = currentPath != null && !result.rootPaths.contains(currentPath),
            onUp = { currentPath = parentOf(currentPath, result.rootPaths) },
        )
        HorizontalDivider()
        if (children.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(children, key = { it.path }) { entry ->
                    FileRow(
                        entry = entry,
                        subtitle = if (entry.isDirectory) {
                            "${result.folderCountByPath[entry.path] ?: 0} items"
                        } else {
                            DateGrouping.dayLabel(entry.modifiedAtMillis)
                        },
                        onClick = { if (entry.isDirectory) currentPath = entry.path },
                        onLongClick = { if (!entry.isDirectory) pendingDelete = entry },
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
private fun PathBar(path: String?, canGoUp: Boolean, onUp: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (canGoUp) {
            IconButton(onClick = onUp) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Up")
            }
        }
        Text(
            text = path ?: "",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

private fun parentOf(path: String?, roots: List<String>): String? {
    if (path == null || roots.contains(path)) return path
    val parent = java.io.File(path).parent ?: return path
    return parent
}

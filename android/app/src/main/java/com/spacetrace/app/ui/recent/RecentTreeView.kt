package com.spacetrace.app.ui.recent

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spacetrace.app.model.FileEntry
import com.spacetrace.app.ui.common.FileRow
import com.spacetrace.app.util.FileTree
import com.spacetrace.app.util.FormatUtils
import com.spacetrace.app.util.TreeNode
import java.text.SimpleDateFormat
import java.util.Locale

private val treeTimeFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
private val indentUnit = 16.dp

private fun indentFor(depth: Int) = (depth - 1).coerceAtLeast(0) * indentUnit.value

@Composable
fun RecentTreeList(
    files: List<FileEntry>,
    roots: List<String>,
    revealPath: String? = null,
    onRevealed: () -> Unit = {},
    onFileLongClick: (FileEntry) -> Unit,
) {
    val tree = remember(files, roots) { FileTree.build(files, roots) }
    var expanded by remember(tree) { mutableStateOf(emptySet<String>()) }
    val visible = remember(tree, expanded) { FileTree.flattenVisible(tree, expanded) }
    val listState = rememberLazyListState()

    // Expand every ancestor folder of the file we're revealing, then once it shows up
    // in the flattened (now-expanded) list, scroll to it.
    LaunchedEffect(revealPath, files, roots) {
        val target = revealPath ?: return@LaunchedEffect
        val entry = files.firstOrNull { it.path == target } ?: return@LaunchedEffect
        expanded = expanded + FileTree.ancestorPaths(entry.parentPath, roots)
    }
    LaunchedEffect(revealPath, visible) {
        val target = revealPath ?: return@LaunchedEffect
        val index = visible.indexOfFirst { it.path == target }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            onRevealed()
        }
    }

    if (visible.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No files found yet. Pull to scan.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        items(visible, key = { it.path }) { node ->
            if (node.isFile) {
                FileRow(
                    entry = node.entry!!,
                    subtitle = treeTimeFormat.format(node.entry.createdAtMillis),
                    path = node.entry.parentPath,
                    modifier = Modifier.padding(start = indentFor(node.depth).dp),
                    onLongClick = { onFileLongClick(node.entry) },
                )
            } else {
                TreeFolderRow(
                    node = node,
                    expanded = expanded.contains(node.path),
                    onClick = {
                        expanded = if (expanded.contains(node.path)) {
                            expanded - node.path
                        } else {
                            expanded + node.path
                        }
                    },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
private fun TreeFolderRow(node: TreeNode, expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = indentFor(node.depth).dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${FormatUtils.formatCount(node.fileCount)} files",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(FormatUtils.formatBytes(node.totalSizeBytes), style = MaterialTheme.typography.labelLarge)
    }
}

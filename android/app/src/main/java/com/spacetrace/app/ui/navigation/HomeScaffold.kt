package com.spacetrace.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spacetrace.app.ui.browse.BrowseScreen
import com.spacetrace.app.ui.recent.RecentScreen
import com.spacetrace.app.ui.storage.StorageScreen
import com.spacetrace.app.viewmodel.ScanViewModel

private enum class HomeTab(val label: String) {
    RECENT("Recent"),
    STORAGE("Storage"),
    BROWSE("Browse"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScaffold(viewModel: ScanViewModel = viewModel()) {
    var tab by remember { mutableStateOf(HomeTab.RECENT) }
    val result by viewModel.result.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val showHidden by viewModel.showHidden.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tab.label) },
                actions = {
                    IconButton(onClick = { viewModel.toggleShowHidden() }) {
                        Icon(
                            imageVector = if (showHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = "Toggle hidden files",
                        )
                    }
                    IconButton(onClick = { viewModel.startScan(force = true) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Rescan")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == HomeTab.RECENT,
                    onClick = { tab = HomeTab.RECENT },
                    icon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                    label = { Text("Recent") },
                )
                NavigationBarItem(
                    selected = tab == HomeTab.STORAGE,
                    onClick = { tab = HomeTab.STORAGE },
                    icon = { Icon(Icons.Filled.PieChart, contentDescription = null) },
                    label = { Text("Storage") },
                )
                NavigationBarItem(
                    selected = tab == HomeTab.BROWSE,
                    onClick = { tab = HomeTab.BROWSE },
                    icon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                    label = { Text("Browse") },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (tab) {
                HomeTab.RECENT -> RecentScreen(
                    result = result,
                    progress = progress,
                    showHidden = showHidden,
                    onDelete = { viewModel.deleteFile(it) },
                )
                HomeTab.STORAGE -> StorageScreen(
                    result = result,
                    showHidden = showHidden,
                    onDelete = { viewModel.deleteFile(it) },
                )
                HomeTab.BROWSE -> BrowseScreen(
                    result = result,
                    showHidden = showHidden,
                    onDelete = { viewModel.deleteFile(it) },
                )
            }
        }
    }
}

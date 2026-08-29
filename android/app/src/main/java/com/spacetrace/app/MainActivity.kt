package com.spacetrace.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spacetrace.app.ui.navigation.HomeScaffold
import com.spacetrace.app.ui.permission.PermissionScreen
import com.spacetrace.app.ui.theme.SpaceTraceTheme
import com.spacetrace.app.util.PermissionUtils
import com.spacetrace.app.viewmodel.ScanViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpaceTraceTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SpaceTraceRoot()
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SpaceTraceRoot(viewModel: ScanViewModel = viewModel()) {
    val hasPermission by viewModel.hasPermission.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = androidx.compose.ui.platform.LocalContext.current

    // Re-check the special permission whenever the app resumes -- it's granted in system
    // Settings, outside any in-app runtime-permission callback. This does NOT trigger a
    // rescan on every resume: the initial scan below already runs once permission becomes
    // true, and after that it's the user's call via the refresh button in the top bar.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (hasPermission) {
        LaunchedEffect(Unit) { viewModel.startScan() }
        HomeScaffold(viewModel)
    } else {
        PermissionScreen(
            onGrantClick = {
                val intent = try {
                    PermissionUtils.allFilesAccessSettingsIntent(context)
                } catch (_: Exception) {
                    PermissionUtils.allFilesAccessFallbackIntent()
                }
                context.startActivity(intent)
            },
        )
    }
}

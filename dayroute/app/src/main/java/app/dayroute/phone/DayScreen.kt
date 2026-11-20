package app.dayroute.phone

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dayroute.data.DayState
import app.dayroute.data.DeviceLocation
import app.dayroute.planner.DayPlan
import app.dayroute.planner.PlannedStop
import app.dayroute.planner.Stop
import app.dayroute.planner.StopStatus
import app.dayroute.planner.Trip
import app.dayroute.repository
import app.dayroute.trip.Navigation
import app.dayroute.trip.TripNotifications
import app.dayroute.trip.TripTrackingService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen() {
    val context = LocalContext.current
    val repo = context.repository
    val state by repo.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<DayPlan?>(null) }
    // A preview is stale as soon as the stop list changes.
    val shownPreview = preview?.takeIf { p -> p.stops.map { it.stop.id }.toSet() == state.stops.map { it.id }.toSet() }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val from = DeviceLocation.lastKnown(context) ?: state.stops.firstOrNull()?.location
        if (from != null) {
            repo.startTrip(from)
            TripTrackingService.start(context)
        }
    }
    fun startTrip() {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.launch(wanted.toTypedArray())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DayRoute") },
                actions = {
                    if (state.trip == null && state.stops.isEmpty()) {
                        TextButton(onClick = {
                            repo.replaceStops(DemoDay.around(DeviceLocation.lastKnown(context)))
                        }) { Text("Try a demo day") }
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.trip == null) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Add stop") },
                )
            }
        },
    ) { padding ->
        val trip = state.trip
        if (trip != null) {
            TripView(trip, state, Modifier.padding(padding))
        } else {
            PlanningView(
                state = state,
                preview = shownPreview,
                onPlan = {
                    val from = DeviceLocation.lastKnown(context) ?: state.stops.first().location
                    preview = repo.preview(from)
                },
                onStart = ::startTrip,
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (adding) {
        AddStopDialog(
            onDismiss = { adding = false },
            onSave = { repo.addStop(it); adding = false },
        )
    }
}

@Composable
private fun PlanningView(
    state: DayState,
    preview: DayPlan?,
    onPlan: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier,
) {
    val repo = LocalContext.current.repository
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                if (state.stops.isEmpty()) "Add the places you need to go today. Give each a time window: strict for appointments, flexible for errands."
                else "Leaving ${state.leaveAt?.let { "at $it" } ?: "now"} · ${state.stops.size} stops",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (preview == null) {
            itemsIndexed(state.stops, key = { _, s -> s.id }) { _, stop ->
                StopCard(stop, onDelete = { repo.removeStop(stop.id) })
            }
            if (state.stops.isNotEmpty()) item {
                Button(onClick = onPlan, modifier = Modifier.fillMaxWidth()) { Text("Plan my day") }
            }
        } else {
            item {
                PlanSummary(preview)
            }
            itemsIndexed(preview.stops, key = { _, p -> p.stop.id }) { i, p ->
                PlannedCard(i + 1, p, status = null)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPlan, modifier = Modifier.weight(1f)) { Text("Re-plan") }
                    Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("Start driving") }
                }
            }
        }
    }
}

@Composable
private fun PlanSummary(plan: DayPlan) {
    val missed = plan.missedStrictStops
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (missed.isEmpty()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("~${plan.totalDriveMinutes} min driving · done by ${plan.finishTime}", fontWeight = FontWeight.SemiBold)
            if (missed.isNotEmpty()) {
                Text("Can't make it in time for: ${missed.joinToString { it.name }}. Consider leaving earlier or dropping a stop.")
            }
        }
    }
}

@Composable
private fun TripView(trip: Trip, state: DayState, modifier: Modifier) {
    val context = LocalContext.current
    val repo = context.repository
    val current = trip.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (current == null) {
                        Text("Day complete!", style = MaterialTheme.typography.headlineSmall)
                        Button(onClick = { repo.endTrip() }) { Text("End day") }
                    } else {
                        Text("Next stop", style = MaterialTheme.typography.labelLarge)
                        Text(current.stop.name, style = MaterialTheme.typography.headlineSmall)
                        Text(TripNotifications.describe(current))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { Navigation.openOnPhone(context, current.stop) }) {
                                Icon(Icons.Default.Navigation, null); Spacer(Modifier.width(4.dp)); Text("Navigate")
                            }
                            FilledTonalButton(onClick = { repo.markArrived() }) { Text("I'm here") }
                            IconButton(onClick = { repo.skipCurrent() }) { Icon(Icons.Default.SkipNext, "Skip") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = state.autoNavigateNext, onCheckedChange = { repo.setAutoNavigateNext(it) })
                            Spacer(Modifier.width(8.dp))
                            Text("Go to next stop automatically on arrival (Android Auto)", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        itemsIndexed(trip.plan.stops, key = { _, p -> p.stop.id }) { i, p ->
            PlannedCard(i + 1, p, trip.statusOf(p.stop.id))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { repo.replan(DeviceLocation.lastKnown(context)) }) { Text("Re-plan from here") }
                TextButton(onClick = { repo.endTrip() }) { Text("End day") }
            }
        }
    }
}

@Composable
private fun StopCard(stop: Stop, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stop.name, fontWeight = FontWeight.SemiBold)
                stop.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                Text(windowLabel(stop), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remove ${stop.name}") }
        }
    }
}

@Composable
private fun PlannedCard(number: Int, p: PlannedStop, status: StopStatus?) {
    val late = p.lateMinutes > 0
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            when (status) {
                StopStatus.VISITED -> Icon(Icons.Default.CheckCircle, "Visited", tint = MaterialTheme.colorScheme.primary)
                StopStatus.SKIPPED -> Icon(Icons.Default.Close, "Skipped")
                StopStatus.PENDING -> Icon(Icons.Default.RadioButtonUnchecked, "To do")
                null -> Text("$number", style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.stop.name, fontWeight = FontWeight.SemiBold)
                Text(
                    TripNotifications.describe(p) + if (p.waitMinutes > 0) " · wait ${p.waitMinutes} min" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

fun windowLabel(stop: Stop): String {
    val w = stop.window
    val time = when {
        w == null || (w.earliest == null && w.latest == null) -> "Any time"
        w.earliest != null && w.latest != null -> "${w.earliest}–${w.latest}"
        w.earliest != null -> "After ${w.earliest}"
        else -> "By ${w.latest}"
    }
    val kind = if (w?.strict == true) "Strict" else "Flexible"
    val stay = if (stop.dwellMinutes > 0) " · stay ${stop.dwellMinutes} min" else ""
    return "$kind · $time$stay"
}

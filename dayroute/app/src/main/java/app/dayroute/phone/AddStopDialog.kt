package app.dayroute.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.dayroute.data.DeviceLocation
import app.dayroute.data.PlaceResult
import app.dayroute.data.Places
import app.dayroute.planner.ClockTime
import app.dayroute.planner.Stop
import app.dayroute.planner.TimeWindow
import kotlinx.coroutines.delay
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddStopDialog(onDismiss: () -> Unit, onSave: (Stop) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<PlaceResult?>(null) }
    var name by remember { mutableStateOf("") }
    var after by remember { mutableStateOf("") }
    var before by remember { mutableStateOf("") }
    var strict by remember { mutableStateOf(false) }
    var stay by remember { mutableStateOf("") }

    LaunchedEffect(query) {
        if (picked != null || query.length < 3) { results = emptyList(); return@LaunchedEffect }
        delay(400) // wait for typing to pause
        searching = true
        results = Places.search(context, query, DeviceLocation.lastKnown(context))
        searching = false
    }

    val afterTime = ClockTime.parseOrNull(after)
    val beforeTime = ClockTime.parseOrNull(before)
    val timesValid = (after.isBlank() || afterTime != null) && (before.isBlank() || beforeTime != null) &&
        (afterTime == null || beforeTime == null || afterTime <= beforeTime)
    val canSave = picked != null && timesValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a stop") },
        confirmButton = {
            TextButton(enabled = canSave, onClick = {
                val place = picked ?: return@TextButton
                onSave(
                    Stop(
                        id = UUID.randomUUID().toString(),
                        name = name.ifBlank { place.label.substringBefore(",") },
                        location = place.point,
                        address = place.label,
                        window = if (afterTime == null && beforeTime == null) null else TimeWindow(afterTime, beforeTime, strict),
                        dwellMinutes = stay.toIntOrNull()?.coerceIn(0, 600) ?: 0,
                    )
                )
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = picked?.label ?: query,
                    onValueChange = { picked = null; query = it },
                    label = { Text("Address or place") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (searching) CircularProgressIndicator(Modifier.padding(4.dp))
                if (picked == null) results.forEach { r ->
                    Text(
                        r.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().clickable { picked = r; results = emptyList() }.padding(vertical = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text("When should you be there?", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = after, onValueChange = { after = it }, label = { Text("Not before") },
                        placeholder = { Text("09:00") }, singleLine = true, isError = after.isNotBlank() && afterTime == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = before, onValueChange = { before = it }, label = { Text("Not after") },
                        placeholder = { Text("17:00") }, singleLine = true, isError = before.isNotBlank() && beforeTime == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = !strict, onClick = { strict = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                        Text("Flexible")
                    }
                    SegmentedButton(selected = strict, onClick = { strict = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                        Text("Strict")
                    }
                }
                Text(
                    if (strict) "An appointment: the plan bends around this time." else "A preference: fine to be a bit late if it saves driving.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = stay, onValueChange = { stay = it.filter(Char::isDigit) },
                    label = { Text("Time spent there (min)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

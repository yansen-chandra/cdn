package io.github.yansenchandra.converter.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yansenchandra.converter.CURRENCY
import io.github.yansenchandra.converter.ConverterViewModel
import io.github.yansenchandra.converter.ConverterViewModel.Side
import io.github.yansenchandra.converter.Line
import io.github.yansenchandra.converter.RatesState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen(vm: ConverterViewModel) {
    var picking by remember { mutableStateOf<Side?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Converter") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SmartInput(vm)
            CategoryRow(vm)
            ManualConverter(vm, onPick = { picking = it })
            if (vm.category == CURRENCY) RatesFooter(vm)
            Spacer(Modifier.height(24.dp))
        }
    }

    picking?.let { side -> UnitPicker(vm, side, onDismiss = { picking = null }) }
}

@Composable
private fun SmartInput(vm: ConverterViewModel) {
    OutlinedTextField(
        value = vm.query,
        onValueChange = vm::onQuery,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Type a conversion") },
        placeholder = { Text("5 ft to cm · 100 sgd to myr") },
        singleLine = true,
        trailingIcon = { if (vm.query.isNotEmpty()) TextButton(onClick = { vm.onQuery("") }) { Text("✕") } },
    )

    val suggestions = vm.suggestions()
    if (suggestions.isNotEmpty()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(suggestions, key = { it.id }) { c ->
                AssistChip(onClick = { vm.applySuggestion(c) }, label = { Text("${c.matched} · ${c.label}") })
            }
        }
    }

    val smart = vm.smart() ?: return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            smart.lines.forEach { ResultRow(it, big = smart.lines.size == 1) }
            smart.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Notes(smart.notes)
            if (smart.lines.isNotEmpty()) {
                TextButton(onClick = vm::applySmart, modifier = Modifier.align(Alignment.End)) { Text("Edit below") }
            }
        }
    }
}

@Composable
private fun CategoryRow(vm: ConverterViewModel) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(vm.categories, key = { it.first }) { (id, name) ->
            FilterChip(selected = vm.category == id, onClick = { vm.selectCategory(id) }, label = { Text(name) })
        }
    }
}

@Composable
private fun ManualConverter(vm: ConverterViewModel, onPick: (Side) -> Unit) {
    val result = vm.manual()
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = vm.amount,
                onValueChange = vm::onAmount,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Amount") },
                textStyle = MaterialTheme.typography.headlineMedium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                UnitButton(vm, vm.from, Modifier.weight(1f)) { onPick(Side.From) }
                TextButton(onClick = vm::swap) { Text("⇄", style = MaterialTheme.typography.titleLarge) }
                UnitButton(vm, vm.to, Modifier.weight(1f)) { onPick(Side.To) }
            }
            result.main?.let { ResultRow(it, big = true) }
            result.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (result.others.isNotEmpty()) {
                HorizontalDivider()
                result.others.forEach { ResultRow(it, big = false) }
            }
            Notes(result.notes)
        }
    }
}

@Composable
private fun UnitButton(vm: ConverterViewModel, id: String, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(vm.symbol(id), fontWeight = FontWeight.Bold, maxLines = 1)
            Text(
                vm.label(id).substringAfter(" · "),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Tap to copy the number. */
@Composable
private fun ResultRow(line: Line, big: Boolean) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { copy(context, line.value) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            line.value,
            style = if (big) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(line.unit, style = MaterialTheme.typography.titleMedium)
        Text(
            line.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Notes(notes: List<String>) {
    notes.forEach {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RatesFooter(vm: ConverterViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val text = when {
            vm.ratesState == RatesState.Loading -> "Updating rates…"
            vm.ratesState == RatesState.Failed && vm.rates != null -> "Offline: using rates from ${vm.rates!!.date}"
            vm.ratesState == RatesState.Failed -> "Couldn't download rates"
            vm.rates != null -> "Rates as of ${vm.rates!!.date}"
            else -> "No rates yet"
        }
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = vm::refreshRates, enabled = vm.ratesState != RatesState.Loading) { Text("Refresh") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitPicker(vm: ConverterViewModel, side: Side, onDismiss: () -> Unit) {
    var search by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val choices = if (search.isBlank()) vm.categoryChoices() else vm.search(search)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                label = { Text(if (side == Side.From) "Convert from" else "Convert to") },
                placeholder = { Text("Search: lbs, sing dollar, kati…") },
                singleLine = true,
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
            if (search.isNotBlank() && choices.isEmpty()) {
                Text("No match for \"$search\"", modifier = Modifier.padding(vertical = 16.dp))
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(choices, key = { "${it.domain}:${it.id}" }) { c ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.pick(c, side)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(c.label, style = MaterialTheme.typography.bodyLarge)
                        Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Result", text))
    // Android 13+ shows its own copy confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied $text", Toast.LENGTH_SHORT).show()
}

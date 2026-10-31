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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yansenchandra.converter.CURRENCY
import io.github.yansenchandra.converter.ConverterViewModel
import io.github.yansenchandra.converter.ConverterViewModel.Side
import io.github.yansenchandra.converter.Line
import io.github.yansenchandra.converter.R
import io.github.yansenchandra.converter.RatesState
import kotlinx.coroutines.delay

/** How long input must stay unchanged before it goes into history. */
private const val SETTLE_MS = 1500L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen(vm: ConverterViewModel) {
    var picking by remember { mutableStateOf<Side?>(null) }
    var showCategories by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = { showHistory = true }, modifier = Modifier.testTag("history_button")) {
                        Text(stringResource(R.string.history))
                    }
                },
            )
        },
    ) { padding ->
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
            CategoryRow(vm, onAll = { showCategories = true })
            PairChips(vm)
            ManualConverter(vm, onPick = { picking = it })
            if (vm.category == CURRENCY) RatesFooter(vm)
            Spacer(Modifier.height(24.dp))
        }
    }

    picking?.let { side -> UnitPicker(vm, side, onDismiss = { picking = null }) }
    if (showCategories) CategorySheet(vm, onDismiss = { showCategories = false })
    if (showHistory) HistorySheet(vm, onDismiss = { showHistory = false })
}

@Composable
private fun SmartInput(vm: ConverterViewModel) {
    val clearLabel = stringResource(R.string.clear)
    OutlinedTextField(
        value = vm.query,
        onValueChange = vm::onQuery,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("query"),
        label = { Text(stringResource(R.string.query_label)) },
        placeholder = { Text(stringResource(R.string.query_placeholder)) },
        singleLine = true,
        trailingIcon = {
            if (vm.query.isNotEmpty()) {
                TextButton(onClick = { vm.onQuery("") }, modifier = Modifier.semantics { contentDescription = clearLabel }) {
                    Text("✕", modifier = Modifier.clearAndSetSemantics {})
                }
            }
        },
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
    LaunchedEffect(vm.query) {
        delay(SETTLE_MS)
        vm.recordSmart()
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.testTag("smart_results"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            smart.lines.forEach { ResultRow(it, big = smart.lines.size == 1) }
            smart.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            smart.choices.filter { it.variants.size > 1 }.forEach { choice ->
                Text(stringResource(R.string.assumed_label, choice.term), style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(choice.variants, key = { it.id }) { v ->
                        FilterChip(
                            selected = v.selected,
                            onClick = { if (!v.selected) vm.chooseVariant(choice, v.id) },
                            label = { Text(v.label) },
                            modifier = Modifier.testTag("variant_${v.id}"),
                        )
                    }
                }
            }
            Notes(smart.notes)
            if (smart.lines.isNotEmpty()) {
                TextButton(onClick = vm::applySmart, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.edit_below))
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(vm: ConverterViewModel, onAll: () -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            AssistChip(onClick = onAll, label = { Text(stringResource(R.string.all_categories)) }, modifier = Modifier.testTag("category_all"))
        }
        items(vm.categories(), key = { it.first }) { (id, name) ->
            FilterChip(
                selected = vm.category == id,
                onClick = { vm.selectCategory(id) },
                label = { Text(name) },
                modifier = Modifier.testTag("category_$id"),
            )
        }
    }
}

@Composable
private fun PairChips(vm: ConverterViewModel) {
    val chips = vm.pairChips()
    if (chips.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(chips, key = { it.pair.encode() }) { c ->
            SuggestionChip(
                onClick = { vm.applyPair(c.pair) },
                label = { Text(if (c.favourite) "★ ${c.label}" else c.label) },
                modifier = Modifier.testTag(if (c.favourite) "pair_favourite" else "pair_recent"),
            )
        }
    }
}

@Composable
private fun ManualConverter(vm: ConverterViewModel, onPick: (Side) -> Unit) {
    val result = vm.manual()
    LaunchedEffect(vm.amount, vm.from, vm.to) {
        delay(SETTLE_MS)
        vm.recordManual()
    }
    val swapLabel = stringResource(R.string.swap)
    val favourite = vm.isFavourite()
    val starLabel = stringResource(if (favourite) R.string.favourite_remove else R.string.favourite_add)
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = vm.amount,
                    onValueChange = vm::onAmount,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("amount"),
                    label = { Text(stringResource(R.string.amount_label)) },
                    textStyle = MaterialTheme.typography.headlineMedium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                TextButton(
                    onClick = vm::toggleFavourite,
                    modifier = Modifier
                        .testTag("favourite_toggle")
                        .semantics { contentDescription = starLabel },
                ) {
                    Text(if (favourite) "★" else "☆", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clearAndSetSemantics {})
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                UnitButton(vm, vm.from, Modifier.weight(1f).testTag("from_button")) { onPick(Side.From) }
                TextButton(
                    onClick = vm::swap,
                    modifier = Modifier
                        .testTag("swap")
                        .semantics { contentDescription = swapLabel },
                ) {
                    Text("⇄", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {})
                }
                UnitButton(vm, vm.to, Modifier.weight(1f).testTag("to_button")) { onPick(Side.To) }
            }
            result.main?.let { ResultRow(it, big = true, modifier = Modifier.testTag("result_main")) }
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
            Text(vm.label(id), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Tap to copy the number. */
@Composable
private fun ResultRow(line: Line, big: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.copy_action)
    val copiedText = stringResource(R.string.copied, line.copy)
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClickLabel = copyLabel) { copy(context, line.copy, copiedText) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            line.value,
            style = if (big) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge,
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
    val rates = vm.rates
    val stale = vm.ratesStale()
    Row(verticalAlignment = Alignment.CenterVertically) {
        val text = when {
            vm.ratesState == RatesState.Loading -> stringResource(R.string.rates_updating)
            vm.ratesState == RatesState.Failed && rates != null -> stringResource(R.string.rates_offline, rates.date)
            vm.ratesState == RatesState.Failed -> stringResource(R.string.rates_failed)
            rates != null && stale -> stringResource(R.string.rates_stale, vm.ratesAgeDays() ?: 0L, rates.date)
            rates != null -> listOfNotNull(vm.ratesMessage, stringResource(R.string.rates_as_of, rates.date)).joinToString(" · ")
            else -> stringResource(R.string.rates_none)
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (stale && vm.ratesState != RatesState.Loading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .testTag("rates_status"),
        )
        TextButton(onClick = vm::refreshRates, enabled = vm.ratesState != RatesState.Loading) {
            Text(stringResource(R.string.refresh))
        }
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
                    .focusRequester(focus)
                    .testTag("picker_search"),
                label = { Text(stringResource(if (side == Side.From) R.string.picker_from else R.string.picker_to)) },
                placeholder = { Text(stringResource(R.string.picker_placeholder)) },
                singleLine = true,
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
            if (search.isNotBlank() && choices.isEmpty()) {
                Text(stringResource(R.string.picker_no_match, search), modifier = Modifier.padding(vertical = 16.dp))
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
                            .padding(vertical = 12.dp)
                            .testTag("choice_${c.id}"),
                    ) {
                        Text(c.label, style = MaterialTheme.typography.bodyLarge)
                        Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySheet(vm: ConverterViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            stringResource(R.string.categories_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(vm.categoryTiles(), key = { it.id }) { t ->
                val selected = t.id == vm.category
                OutlinedCard(
                    onClick = {
                        vm.selectCategory(t.id)
                        onDismiss()
                    },
                    colors = if (selected) {
                        CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    } else {
                        CardDefaults.outlinedCardColors()
                    },
                    modifier = Modifier.testTag("category_tile_${t.id}"),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.units_count, t.count),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(vm: ConverterViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.history), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (vm.history.isNotEmpty()) {
                    TextButton(onClick = vm::clearHistory) { Text(stringResource(R.string.clear_history)) }
                }
            }
            if (vm.history.isEmpty()) {
                Text(stringResource(R.string.history_empty), modifier = Modifier.padding(vertical = 16.dp))
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(vm.history, key = { it.label }) { e ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.applyHistory(e)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp)
                            .testTag("history_entry"),
                    ) {
                        Text(e.label, style = MaterialTheme.typography.bodyLarge)
                        Text(e.result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun copy(context: Context, text: String, confirmation: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Result", text))
    // Android 13+ shows its own copy confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
}

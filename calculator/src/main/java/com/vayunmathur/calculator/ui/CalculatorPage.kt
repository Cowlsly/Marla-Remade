package com.vayunmathur.calculator.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.calculator.R
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import com.vayunmathur.calculator.util.AngleMode
import com.vayunmathur.calculator.util.CalculatorActions
import com.vayunmathur.calculator.util.CalculatorUiState
import com.vayunmathur.calculator.util.CalculatorViewModel
import com.vayunmathur.calculator.util.HistoryEntry
import com.vayunmathur.calculator.util.renderInputForDisplay
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ButtonDefaults
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconRuler
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.PrimaryScrollableTabRow
import com.vayunmathur.library.ui.Tab
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

private enum class KeyEmphasis { Digit, Operator, Primary, Function, Toggle }

/** Which date/time picker (if any) is currently open. `DtDate`/`DtTime` are the two steps of
 * the combined date-and-time flow. */
private enum class Picker { None, Date, Time, DtDate, DtTime }

/**
 * A keypad key. [second]/[secondPress] give an alternate label+action shown when the
 * "2nd" modifier is active (e.g. sin → sin⁻¹). [weight] lets a key span extra columns.
 *
 * [onPress] is last so the common single-action key reads as a trailing lambda:
 * `Key("7") { it.append("7") }`.
 */
private class Key(
    val label: String,
    val emphasis: KeyEmphasis = KeyEmphasis.Digit,
    val weight: Float = 1f,
    val second: String? = null,
    val secondPress: ((CalculatorActions) -> Unit)? = null,
    val onPress: (CalculatorActions) -> Unit,
)

@Composable
private fun RowScope.KeyButton(key: Key, actions: CalculatorActions, second: Boolean, onToggleSecond: () -> Unit) {
    val showSecond = second && key.second != null
    val label = if (showSecond) key.second else key.label
    val secondActive = key.emphasis == KeyEmphasis.Toggle && second
    val colors = when (key.emphasis) {
        KeyEmphasis.Digit -> ButtonDefaults.filledTonalButtonColors()
        KeyEmphasis.Operator -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        KeyEmphasis.Primary -> ButtonDefaults.buttonColors()
        KeyEmphasis.Function -> ButtonDefaults.textButtonColors()
        KeyEmphasis.Toggle -> if (secondActive) ButtonDefaults.buttonColors() else ButtonDefaults.textButtonColors()
    }
    Button(
        onClick = {
            when {
                key.emphasis == KeyEmphasis.Toggle -> onToggleSecond()
                showSecond -> key.secondPress?.invoke(actions)
                else -> key.onPress(actions)
            }
        },
        modifier = Modifier.weight(key.weight).height(50.dp).padding(2.dp),
        shape = RoundedCornerShape(16.dp),
        colors = colors,
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, fontSize = if (label.length >= 4) 13.sp else 18.sp, maxLines = 1)
    }
}

/** Binds [CalculatorViewModel] to the stateless [CalculatorScreen]. */
@Composable
fun CalculatorPage(viewModel: CalculatorViewModel) {
    CalculatorScreen(state = viewModel.calculatorUiState, actions = viewModel)
}

/**
 * The keypad screen, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@Composable
fun CalculatorScreen(
    state: CalculatorUiState,
    actions: CalculatorActions,
    /**
     * Seeds for the screen's own UI-only state (which dialog is open, whether the "2nd"
     * modifier is latched). The app always takes the defaults; previews set them so a
     * given screen can be captured without driving the UI to get there.
     */
    initialShowHistory: Boolean = false,
    initialSecond: Boolean = false,
    initialShowUnitPicker: Boolean = false,
) {
    var showHistory by remember { mutableStateOf(initialShowHistory) }
    var second by remember { mutableStateOf(initialSecond) }
    var showUnitPicker by remember { mutableStateOf(initialShowUnitPicker) }
    var picker by remember { mutableStateOf(Picker.None) }
    // The date chosen in the first step of the combined date-and-time flow (UTC midnight millis).
    var dtDateMillis by remember { mutableStateOf<Long?>(null) }

    fun ins(text: String): (CalculatorActions) -> Unit = { it.append(text) }

    val rows: List<List<Key>> = listOf(
        listOf(
            Key("2nd", KeyEmphasis.Toggle) {},
            Key("π", KeyEmphasis.Function, second = "φ", onPress = ins("π"), secondPress = ins("phi")),
            Key("e", KeyEmphasis.Function, second = "τ", onPress = ins("e"), secondPress = ins("tau")),
            Key("!", KeyEmphasis.Function) { it.append("!") },
            Key("AC", KeyEmphasis.Operator) { it.clear() },
        ),
        listOf(
            Key("sin", KeyEmphasis.Function, second = "sin⁻¹", onPress = ins("sin("), secondPress = ins("asin(")),
            Key("cos", KeyEmphasis.Function, second = "cos⁻¹", onPress = ins("cos("), secondPress = ins("acos(")),
            Key("tan", KeyEmphasis.Function, second = "tan⁻¹", onPress = ins("tan("), secondPress = ins("atan(")),
            Key("ln", KeyEmphasis.Function, second = "eˣ", onPress = ins("ln("), secondPress = ins("e^(")),
            Key("log", KeyEmphasis.Function, second = "10ˣ", onPress = ins("log("), secondPress = ins("10^(")),
        ),
        listOf(
            Key("^", KeyEmphasis.Function, second = "x²", onPress = ins("^"), secondPress = ins("^2")),
            Key("√", KeyEmphasis.Function, second = "∛", onPress = ins("√("), secondPress = ins("cbrt(")),
            Key("(", KeyEmphasis.Function) { it.append("(") },
            Key(")", KeyEmphasis.Function) { it.append(")") },
            Key(",", KeyEmphasis.Function, second = "EE", onPress = ins(","), secondPress = ins("E")),
        ),
        listOf(
            Key("MC", KeyEmphasis.Function) { it.memoryClear() },
            Key("MR", KeyEmphasis.Function) { it.memoryRecall() },
            Key("M+", KeyEmphasis.Function) { it.memoryAdd() },
            Key("M-", KeyEmphasis.Function) { it.memorySubtract() },
            Key(if (state.angleMode == AngleMode.DEGREES) "DEG" else "RAD", KeyEmphasis.Function) { it.toggleAngleMode() },
        ),
        listOf(
            Key("7") { it.append("7") },
            Key("8") { it.append("8") },
            Key("9") { it.append("9") },
            Key("÷", KeyEmphasis.Operator) { it.append("/") },
            Key("⌫", KeyEmphasis.Operator) { it.backspace() },
        ),
        listOf(
            Key("4") { it.append("4") },
            Key("5") { it.append("5") },
            Key("6") { it.append("6") },
            Key("×", KeyEmphasis.Operator) { it.append("*") },
            Key("%", KeyEmphasis.Operator) { it.append("%") },
        ),
        listOf(
            Key("1") { it.append("1") },
            Key("2") { it.append("2") },
            Key("3") { it.append("3") },
            Key("−", KeyEmphasis.Operator) { it.append("-") },
            Key("nCr", KeyEmphasis.Function, second = "nPr", onPress = ins("nCr("), secondPress = ins("nPr(")),
        ),
        listOf(
            Key("ans", KeyEmphasis.Function) { it.append("ans") },
            Key("0") { it.append("0") },
            Key(".") { it.append(".") },
            Key("+", KeyEmphasis.Operator) { it.append("+") },
            Key("=", KeyEmphasis.Primary) { it.evaluate() },
        ),
    )

    AppScaffold(
        title = {},
        alignment = AppBarAlignment.Center,
        actions = {
            IconButton({ showUnitPicker = true }) { IconRuler() }
            IconButton({ showHistory = true }) { IconHistory() }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ---- Display ----
            Column(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.End,
            ) {
                if (state.memory != 0.0) {
                    Text("M", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                }
                Text(
                    renderInputForDisplay(state.input).ifEmpty { "0" }
                        .replace("*", "×").replace("/", "÷").replace("-", "−"),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    textAlign = TextAlign.End,
                    fontSize = 38.sp,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    state.preview,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    textAlign = TextAlign.End,
                    fontSize = 24.sp,
                    maxLines = 1,
                    softWrap = false,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.unitOptions.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.convert_to),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.unitOptions.forEach { unit ->
                            FilterChip(
                                selected = unit == state.selectedUnit,
                                onClick = { actions.selectOutputUnit(unit.token) },
                                label = { Text(unit.symbol) },
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // ---- Keypad ----
            Column(Modifier.fillMaxWidth().padding(4.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    PickerKey(stringResource(R.string.key_date)) { picker = Picker.Date }
                    PickerKey(stringResource(R.string.key_time)) { picker = Picker.Time }
                    PickerKey(stringResource(R.string.key_datetime)) { picker = Picker.DtDate }
                }
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { key -> KeyButton(key, actions, second) { second = !second } }
                    }
                }
            }
        }
    }

    if (showHistory) HistoryDialog(state.history, actions) { showHistory = false }
    if (showUnitPicker) UnitPickerSheet(state, actions) { showUnitPicker = false }

    when (picker) {
        Picker.Date -> DatePickerModal(onDismiss = { picker = Picker.None }) { millis ->
            actions.insertInstant(localMidnightSeconds(millis))
            picker = Picker.None
        }
        Picker.DtDate -> DatePickerModal(onDismiss = { picker = Picker.None }) { millis ->
            dtDateMillis = millis
            picker = Picker.DtTime
        }
        Picker.Time -> TimePickerModal(onDismiss = { picker = Picker.None }) { hour, minute ->
            actions.insertDuration((hour * 3600 + minute * 60).toLong())
            picker = Picker.None
        }
        Picker.DtTime -> TimePickerModal(onDismiss = { picker = Picker.None }) { hour, minute ->
            dtDateMillis?.let { actions.insertInstant(combineDateTimeSeconds(it, hour, minute)) }
            picker = Picker.None
        }
        Picker.None -> {}
    }
}

/** A keypad button that opens a picker (styled like a Function key, but driven by screen state
 * rather than a [CalculatorActions] call, so it can toggle a dialog). */
@Composable
private fun RowScope.PickerKey(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f).height(50.dp).padding(2.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.textButtonColors(),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, fontSize = if (label.length >= 4) 13.sp else 18.sp, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerModal(onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val dateState = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { dateState.selectedDateMillis?.let(onConfirm) ?: onDismiss() }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(UiR.string.cancel)) } },
    ) {
        DatePicker(state = dateState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerModal(onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    val timeState = rememberTimePickerState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_time)) },
        text = { TimePicker(state = timeState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(timeState.hour, timeState.minute) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(UiR.string.cancel)) } },
    )
}

/** The system date picker returns UTC midnight of the chosen day; reinterpret that calendar day
 * at local midnight so the stored instant reads back as that date. */
private fun localMidnightSeconds(utcDateMillis: Long): Long =
    Instant.ofEpochMilli(utcDateMillis).atZone(ZoneOffset.UTC).toLocalDate()
        .atStartOfDay(ZoneId.systemDefault()).toEpochSecond()

/** Combine a picked calendar day (UTC midnight millis) with a local time-of-day into an instant. */
private fun combineDateTimeSeconds(utcDateMillis: Long, hour: Int, minute: Int): Long =
    Instant.ofEpochMilli(utcDateMillis).atZone(ZoneOffset.UTC).toLocalDate()
        .atTime(hour, minute).atZone(ZoneId.systemDefault()).toEpochSecond()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnitPickerSheet(
    state: CalculatorUiState,
    actions: CalculatorActions,
    onDismiss: () -> Unit,
) {
    val categories = state.unitCategories.filter { it.inEquations }
    var categoryIndex by remember { mutableStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.insert_unit),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 20.sp,
        )
        if (categories.isNotEmpty()) {
            PrimaryScrollableTabRow(selectedTabIndex = categoryIndex) {
                categories.forEachIndexed { index, category ->
                    Tab(
                        selected = index == categoryIndex,
                        onClick = { categoryIndex = index },
                        text = { Text(category.name) },
                    )
                }
            }
            categories.getOrNull(categoryIndex)?.let { category ->
                FlowRow(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    category.units.forEach { unit ->
                        AssistChip(
                            onClick = { actions.append(unit.token); onDismiss() },
                            label = { Text(unit.symbol) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryDialog(
    history: List<HistoryEntry>,
    actions: CalculatorActions,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history)) },
        text = {
            if (history.isEmpty()) {
                Text(stringResource(R.string.no_calculations_yet))
            } else {
                LazyColumn(Modifier.height(320.dp)) {
                    items(history) { entry ->
                        Card(
                            onClick = { actions.useHistory(entry); onDismiss() },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    entry.expression,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp,
                                )
                                Text("= ${entry.result}", fontSize = 20.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(UiR.string.close)) } },
        dismissButton = {
            if (history.isNotEmpty()) {
                TextButton({ actions.clearHistory(); onDismiss() }) { Text(stringResource(UiR.string.clear)) }
            }
        },
    )
}

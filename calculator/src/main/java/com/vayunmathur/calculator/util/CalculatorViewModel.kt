package com.vayunmathur.calculator.util

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.launch
import kotlin.math.hypot

/** One plotted curve on the graph screen — Cartesian `y = f(x)` or polar `r = f(θ)`. */
data class GraphFunction(
    val id: Long,
    val text: String = "",
    val color: Color,
    val enabled: Boolean = true,
    val polar: Boolean = false,
)

/**
 * A notable point the user revealed by touching near it. Held as the feature itself rather
 * than as pre-rendered text so the UI can colour and localise the label.
 */
data class GraphMarker(
    val point: GraphPoint,
    val kind: FeatureKind,
    /** The curve it belongs to, or the two curves that cross there. */
    val curveIds: List<Long>,
)

/**
 * Holds all calculator state at the Activity scope so it survives switching between the
 * Calculator and Graph tabs (which reset the nav back stack).
 */
class CalculatorViewModel(application: Application) :
    AndroidViewModel(application), CalculatorActions, GraphActions, UnitConverterActions {

    private val dataStore = DataStoreUtils.getInstance(application)

    // ---- Shared ----
    var angleMode by mutableStateOf(AngleMode.RADIANS)
        private set

    override fun toggleAngleMode() {
        angleMode = if (angleMode == AngleMode.RADIANS) AngleMode.DEGREES else AngleMode.RADIANS
        recomputePreview()
    }

    // ---- Calculator tab ----
    var input by mutableStateOf("")
        private set

    /** Live-evaluated preview of [input]; empty when blank or invalid. Unit-aware. */
    var preview by mutableStateOf("")
        private set

    /** The last successfully computed value, exposed to expressions as `ans`. */
    var lastAnswer by mutableStateOf(0.0)
        private set

    /** The single memory register (M+ / M- / MR / MC). */
    var memory by mutableStateOf(0.0)
        private set

    /** Units the current result can be shown in; empty when the result is dimensionless. */
    var unitOptions by mutableStateOf<List<UnitDef>>(emptyList())
        private set

    /** The unit [preview] is currently rendered in. */
    var selectedUnit by mutableStateOf<UnitDef?>(null)
        private set

    /** The most recent parsed preview value, kept so switching output units is instant. */
    private var previewQuantity: Quantity? = null

    val history = mutableStateListOf<HistoryEntry>()

    /**
     * Snapshot of everything [com.vayunmathur.calculator.ui.CalculatorScreen] draws. Read
     * during composition, so the underlying `mutableStateOf` reads are tracked normally.
     */
    val calculatorUiState: CalculatorUiState
        get() = CalculatorUiState(
            input = input,
            preview = preview,
            memory = memory,
            angleMode = angleMode,
            history = history,
            unitOptions = unitOptions,
            selectedUnit = selectedUnit,
            unitCategories = UnitRegistry.categories,
        )

    /** Snapshot of everything [com.vayunmathur.calculator.ui.GraphScreen] draws. */
    val graphUiState: GraphUiState
        get() = GraphUiState(
            functions = functions,
            markers = markers,
            angleMode = angleMode,
            viewport = GraphViewport(centerX, centerY, scale),
        )

    fun updateInput(value: String) {
        input = value
        recomputePreview()
    }

    override fun append(text: String) = updateInput(input + text)

    override fun insertInstant(epochSeconds: Long) = append("#$epochSeconds")

    /** Insert a duration (e.g. a picked time of day) as a re-parsable `(Hh+Mmin+Ss)` group. */
    override fun insertDuration(seconds: Long) {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = buildList {
            if (h != 0L) add("${h}h")
            if (m != 0L) add("${m}min")
            if (s != 0L) add("${s}s")
        }
        append(if (parts.isEmpty()) "0s" else "(" + parts.joinToString("+") + ")")
    }

    override fun backspace() {
        if (input.isNotEmpty()) updateInput(input.dropLast(1))
    }

    override fun clear() = updateInput("")

    /** Evaluate the current input, push it to history, store `ans`, show the result. */
    override fun evaluate() {
        if (input.isBlank()) return
        val quantity = try {
            Expression.parse(input).evalQuantity(angle = angleMode, ans = lastAnswer)
        } catch (e: ExpressionError) {
            return // leave input untouched; the preview already flagged the problem
        }
        if (quantity.value.isNaN()) return
        val isInstant = quantity.instant
        val unit = if (quantity.isDimensionless || isInstant) {
            null
        } else {
            selectedUnit ?: UnitRegistry.defaultUnitFor(quantity.dimension)
        }
        val display = formatQuantity(quantity, unit)
        history.add(0, HistoryEntry(input, display))
        lastAnswer = quantity.value
        if (unit != null) selectedUnit = unit
        // The token form re-parses, so the result can seed the next calculation.
        updateInput(formatQuantity(quantity, unit, useToken = true))
    }

    override fun useHistory(entry: HistoryEntry) = updateInput(entry.result)

    override fun clearHistory() = history.clear()

    override fun selectOutputUnit(token: String) {
        val unit = unitOptions.firstOrNull { it.token == token } ?: return
        selectedUnit = unit
        previewQuantity?.let { preview = formatQuantity(it, unit) }
    }

    // Memory register operations. M+/M- fold the current preview (or input) into memory.
    override fun memoryClear() { memory = 0.0 }
    override fun memoryRecall() = append(formatResult(memory))
    override fun memoryAdd() { currentValue()?.let { memory += it } }
    override fun memorySubtract() { currentValue()?.let { memory -= it } }

    private fun currentValue(): Double? = try {
        Expression.parse(input.ifBlank { "0" }).eval(angle = angleMode, ans = lastAnswer)
            .takeIf { !it.isNaN() }
    } catch (e: ExpressionError) { null }

    /** Re-derive [preview], [unitOptions] and [selectedUnit] from the current [input]. */
    private fun recomputePreview() {
        val quantity = try {
            if (input.isBlank()) null
            else Expression.parse(input).evalQuantity(angle = angleMode, ans = lastAnswer)
                .takeIf { !it.value.isNaN() }
        } catch (e: ExpressionError) {
            null
        }
        previewQuantity = quantity
        if (quantity == null || quantity.isDimensionless) {
            unitOptions = emptyList()
            selectedUnit = null
            preview = if (quantity == null) "" else formatResult(quantity.value)
        } else if (quantity.instant) {
            // A date/datetime has no unit chips; it renders as localized text.
            unitOptions = emptyList()
            selectedUnit = null
            preview = formatInstant(quantity.value)
        } else {
            val options = UnitRegistry.unitsFor(quantity.dimension)
            unitOptions = options
            if (selectedUnit !in options) selectedUnit = UnitRegistry.defaultUnitFor(quantity.dimension)
            preview = formatQuantity(quantity, selectedUnit)
        }
    }

    // ---- Graph tab ----
    val functions = mutableStateListOf(
        GraphFunction(id = 0, text = "x", color = FunctionColors[0]),
    )
    private var nextId = 1L

    override fun addFunction() {
        val color = FunctionColors[functions.size % FunctionColors.size]
        functions.add(GraphFunction(id = nextId++, text = "", color = color))
    }

    override fun updateFunction(id: Long, text: String) {
        dropMarkersFor(id)
        mutate(id) { it.copy(text = text) }
    }

    override fun toggleFunction(id: Long) {
        dropMarkersFor(id)
        mutate(id) { it.copy(enabled = !it.enabled) }
    }

    override fun togglePolar(id: Long) {
        dropMarkersFor(id)
        mutate(id) { it.copy(polar = !it.polar) }
    }

    override fun removeFunction(id: Long) {
        functions.removeAll { it.id == id }
        if (functions.isEmpty()) addFunction()
        dropMarkersFor(id)
    }

    private inline fun mutate(id: Long, transform: (GraphFunction) -> GraphFunction) {
        val index = functions.indexOfFirst { it.id == id }
        if (index >= 0) functions[index] = transform(functions[index])
    }

    // ---- Viewport (hoisted here so it survives tab switches and analysis can read it) ----
    var centerX by mutableStateOf(0.0)
    var centerY by mutableStateOf(0.0)
    var scale by mutableStateOf(60.0) // pixels per unit
    var viewWidthPx by mutableStateOf(0f)
    var viewHeightPx by mutableStateOf(0f)

    /** Notable points the user has revealed by touching near them. */
    val markers = mutableStateListOf<GraphMarker>()

    override fun setViewSize(widthPx: Float, heightPx: Float) {
        viewWidthPx = widthPx
        viewHeightPx = heightPx
    }

    override fun setViewport(centerX: Double, centerY: Double, scale: Double) {
        this.centerX = centerX
        this.centerY = centerY
        this.scale = scale
    }

    private fun dropMarkersFor(id: Long) = markers.removeAll { id in it.curveIds }

    // ---- Units tab (converter) ----
    var converterCategoryIndex by mutableStateOf(0)
        private set
    var converterFromToken by mutableStateOf(UnitRegistry.categories[0].units[0].token)
        private set
    var converterToToken by mutableStateOf(
        UnitRegistry.categories[0].units.getOrElse(1) { UnitRegistry.categories[0].units[0] }.token,
    )
        private set
    var converterValueText by mutableStateOf("1")
        private set

    // Live currency rates power a "Currency" tab appended after the static categories. The tab
    // is always present (so it stays reachable while loading or after a failure); its units
    // populate once rates are fetched.
    private var currencyCategory by mutableStateOf<UnitCategory?>(null)
    private var currencyLoading by mutableStateOf(false)
    private var currencyError by mutableStateOf<String?>(null)

    /**
     * The pair last converted in each category, keyed by category name. Mirrors what is persisted
     * so switching tabs never has to touch the (asynchronously hydrated) preference snapshot.
     */
    private val lastUnits = mutableMapOf<String, Pair<String, String>>()

    /** Static physical-unit categories plus the (possibly still-empty) live Currency tab. */
    private val converterCategories: List<UnitCategory>
        get() = UnitRegistry.categories + (currencyCategory ?: EMPTY_CURRENCY_CATEGORY)

    private val currencyIndex: Int get() = converterCategories.lastIndex

    val unitConverterUiState: UnitConverterUiState
        get() {
            val categories = converterCategories
            val index = converterCategoryIndex.coerceIn(categories.indices)
            return UnitConverterUiState(
                categories = categories,
                selectedCategoryIndex = index,
                fromToken = converterFromToken,
                toToken = converterToToken,
                inputText = converterValueText,
                outputText = convert(categories[index]),
                currencyLoading = currencyLoading,
                currencyError = currencyError,
                isCurrencyCategory = index == currencyIndex,
            )
        }

    private fun convert(category: UnitCategory): String {
        val from = category.units.firstOrNull { it.token == converterFromToken } ?: return ""
        val to = category.units.firstOrNull { it.token == converterToToken } ?: return ""
        val value = converterValueText.toDoubleOrNull() ?: return ""
        return formatResult(to.fromBase(from.toBase(value)))
    }

    override fun selectCategory(index: Int) {
        val categories = converterCategories
        if (index !in categories.indices) return
        converterCategoryIndex = index
        applyRememberedUnits(categories[index])
        persist(KEY_UNITS_CATEGORY, categories[index].name)
    }

    /**
     * Restores the pair the user last converted in [category], falling back to its first two units.
     * A remembered token is dropped if the category no longer offers it, which happens when a
     * currency disappears from the rate list.
     */
    private fun applyRememberedUnits(category: UnitCategory) {
        val units = category.units
        val remembered = rememberedUnits(category.name)
        val defaultFrom = units.getOrNull(0)?.token ?: ""
        val defaultTo = units.getOrElse(1) { units.getOrNull(0) }?.token ?: defaultFrom
        converterFromToken = remembered?.first?.takeIf { token -> units.any { it.token == token } }
            ?: defaultFrom
        converterToToken = remembered?.second?.takeIf { token -> units.any { it.token == token } }
            ?: defaultTo
    }

    private fun rememberedUnits(categoryName: String): Pair<String, String>? {
        lastUnits[categoryName]?.let { return it }
        val from = dataStore.getString(unitsFromKey(categoryName)) ?: return null
        val to = dataStore.getString(unitsToKey(categoryName)) ?: return null
        return (from to to).also { lastUnits[categoryName] = it }
    }

    private fun rememberUnits() {
        val categories = converterCategories
        val name = categories[converterCategoryIndex.coerceIn(categories.indices)].name
        lastUnits[name] = converterFromToken to converterToToken
        persist(unitsFromKey(name), converterFromToken)
        persist(unitsToKey(name), converterToToken)
    }

    private fun persist(key: String, value: String) {
        viewModelScope.launch { dataStore.setString(key, value) }
    }

    override fun setFrom(token: String) {
        converterFromToken = token
        rememberUnits()
    }

    override fun setTo(token: String) {
        converterToToken = token
        rememberUnits()
    }

    override fun setConverterInput(text: String) { converterValueText = text }
    override fun swapUnits() {
        val categories = converterCategories
        val swappedInput = convert(categories[converterCategoryIndex.coerceIn(categories.indices)])
        val from = converterFromToken
        converterFromToken = converterToToken
        converterToToken = from
        if (swappedInput.toDoubleOrNull() != null) converterValueText = swappedInput
        rememberUnits()
    }

    override fun retryCurrency() = loadCurrencyRates()

    private fun loadCurrencyRates() {
        if (currencyLoading) return
        currencyLoading = true
        currencyError = null
        viewModelScope.launch {
            runCatching { CurrencyApi.rates() }
                .onSuccess { dto ->
                    val category = UnitRegistry.currencyCategory(dto.rates)
                    if (category == null) {
                        currencyError = "No exchange rates available"
                    } else {
                        val wasEmpty = currencyCategory?.units.isNullOrEmpty()
                        currencyCategory = category
                        // If the user is already on the Currency tab with nothing picked yet, fill
                        // in their remembered pair (or USD -> EUR) now that units exist.
                        if (converterCategoryIndex == currencyIndex && wasEmpty) {
                            applyRememberedUnits(category)
                        }
                    }
                }
                .onFailure { currencyError = "Couldn't load exchange rates" }
            currencyLoading = false
        }
    }

    init {
        loadCurrencyRates()
        restoreConverterSelection()
    }

    /**
     * Reopens the converter on the tab and unit pair the user left it on. Uses the awaiting getters
     * because at construction the mirrored preference snapshot may not be hydrated yet.
     */
    private fun restoreConverterSelection() {
        viewModelScope.launch {
            val name = dataStore.getStringAwait(KEY_UNITS_CATEGORY) ?: return@launch
            val from = dataStore.getStringAwait(unitsFromKey(name))
            val to = dataStore.getStringAwait(unitsToKey(name))
            if (from != null && to != null) lastUnits.putIfAbsent(name, from to to)

            val categories = converterCategories
            val index = categories.indexOfFirst { it.name == name }
            if (index < 0) return@launch
            converterCategoryIndex = index
            // Currency has no units until rates arrive; loadCurrencyRates applies them then.
            if (categories[index].units.isNotEmpty()) applyRememberedUnits(categories[index])
        }
    }

    /**
     * Samples every visible curve over the current viewport. Drawing and analysis share this
     * so a marker always lands exactly on the line that was drawn.
     */
    fun sampleCurves(widthPx: Float, heightPx: Float): List<SampledCurve> {
        if (widthPx <= 0f || heightPx <= 0f) return emptyList()
        val xMin = centerX - (widthPx / 2) / scale
        val xMax = centerX + (widthPx / 2) / scale
        val yMin = centerY - (heightPx / 2) / scale
        val yMax = centerY + (heightPx / 2) / scale
        return functions.filter { it.enabled && it.text.isNotBlank() }.mapNotNull { fn ->
            val expr = runCatching { Expression.parse(fn.text) }.getOrNull() ?: return@mapNotNull null
            GraphAnalysis.sample(fn.id, expr, fn.polar, angleMode, xMin, xMax, yMin, yMax, widthPx.toInt())
        }
    }

    /**
     * Handles a tap on the graph, [radius] being the touch slop in graph units. Touching a
     * marker removes it; touching anywhere else reveals the nearest notable point in range,
     * across every curve on screen — Cartesian, polar, and crossings between the two.
     */
    override fun tapGraph(at: GraphPoint, radius: Double) {
        val hit = markers.minByOrNull { hypot(it.point.x - at.x, it.point.y - at.y) }
        if (hit != null && hypot(hit.point.x - at.x, hit.point.y - at.y) <= radius) {
            markers.remove(hit)
            return
        }
        val feature = GraphAnalysis
            .featuresNear(sampleCurves(viewWidthPx, viewHeightPx), at, radius, angleMode)
            .firstOrNull() ?: return
        markers.add(GraphMarker(feature.point, feature.kind, feature.curveIds))
    }

    companion object {
        /** Distinct, colour-blind-friendly curve colours, reused cyclically. */
        val FunctionColors = listOf(
            Color(0xFF4285F4), Color(0xFFEA4335), Color(0xFF34A853),
            Color(0xFFFF9800), Color(0xFF9C27B0), Color(0xFF00BCD4),
        )

        /** The Currency tab's stand-in before rates load: present so the tab shows, but empty. */
        private val EMPTY_CURRENCY_CATEGORY =
            UnitCategory("Currency", emptyList(), inEquations = false)

        private const val KEY_UNITS_CATEGORY = "calculator_units_category"

        private fun unitsFromKey(categoryName: String) = "calculator_units_from_$categoryName"

        private fun unitsToKey(categoryName: String) = "calculator_units_to_$categoryName"
    }
}

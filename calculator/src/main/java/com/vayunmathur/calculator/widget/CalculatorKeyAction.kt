package com.vayunmathur.calculator.widget

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.vayunmathur.calculator.util.Expression
import com.vayunmathur.calculator.util.ExpressionError
import com.vayunmathur.calculator.util.UnitRegistry
import com.vayunmathur.calculator.util.formatQuantity

/** The in-progress expression, in the same re-parsable form the app's input line uses. */
internal val CalculatorInputKey = stringPreferencesKey("calculator_widget_input")

/** The last computed result, so `ans` resolves the same way it does in the app. */
internal val CalculatorAnswerKey = doublePreferencesKey("calculator_widget_answer")

/** Which key was tapped, as one of the `COMMAND_*` values below. */
internal val CalculatorKeyParam = ActionParameters.Key<String>("calculator_widget_key")

internal const val COMMAND_CLEAR = "clear"
internal const val COMMAND_BACKSPACE = "backspace"
internal const val COMMAND_EVALUATE = "evaluate"

/** Prefixed rather than bare so an appended "=" or "clear" could never be mistaken for a command. */
internal const val COMMAND_APPEND_PREFIX = "append:"

/**
 * A result of the app's evaluator, in both the pretty form for the display and the ASCII
 * token form that re-parses so it can seed the next calculation.
 */
internal class WidgetResult(val display: String, val token: String, val value: Double)

/**
 * Evaluates [input] with [com.vayunmathur.calculator.util.Expression], the same parser the
 * app uses. Returns null for a blank, unparsable or undefined expression, which the widget
 * renders as an empty result line.
 */
internal fun evaluateForWidget(input: String, ans: Double): WidgetResult? {
    if (input.isBlank()) return null
    val quantity = try {
        Expression.parse(input).evalQuantity(ans = ans)
    } catch (e: ExpressionError) {
        return null
    }
    if (quantity.value.isNaN()) return null
    val unit = if (quantity.isDimensionless || quantity.instant) {
        null
    } else {
        UnitRegistry.defaultUnitFor(quantity.dimension)
    }
    return WidgetResult(
        display = formatQuantity(quantity, unit),
        token = formatQuantity(quantity, unit, useToken = true),
        value = quantity.value,
    )
}

/**
 * Handles every keypad tap. Glance dispatches this through the widget's own receiver, so the
 * only state that survives a tap is what gets written to the widget's preferences here.
 */
class CalculatorKeyAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val command = parameters[CalculatorKeyParam] ?: return
        updateAppWidgetState(context, glanceId) { prefs ->
            val input = prefs[CalculatorInputKey].orEmpty()
            when {
                command == COMMAND_CLEAR -> prefs[CalculatorInputKey] = ""
                command == COMMAND_BACKSPACE -> prefs[CalculatorInputKey] = input.dropLast(1)
                command == COMMAND_EVALUATE -> {
                    val result = evaluateForWidget(input, prefs[CalculatorAnswerKey] ?: 0.0)
                    if (result != null) {
                        prefs[CalculatorInputKey] = result.token
                        prefs[CalculatorAnswerKey] = result.value
                    }
                }
                command.startsWith(COMMAND_APPEND_PREFIX) ->
                    prefs[CalculatorInputKey] = input + command.removePrefix(COMMAND_APPEND_PREFIX)
            }
        }
        // Writing state alone does not reliably recompose; the explicit update does.
        CalculatorGlanceWidget().update(context, glanceId)
    }
}

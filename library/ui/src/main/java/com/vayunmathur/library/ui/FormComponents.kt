package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation

/**
 * The building blocks of a form screen.
 *
 * Editing screens were each assembling the same shapes by hand: a padded
 * [Column] with `Spacer`s of drifting heights between fields, and an
 * `OutlinedTextField(..., modifier = Modifier.fillMaxWidth())` written out in
 * full every time. [FormSection] fixes the outer padding and the gap between
 * fields ([Spacing.lg] / [Spacing.md]); [LabeledTextField] is the full-width
 * field with a label that a form actually wants.
 *
 * Kept thin on purpose - a form is a stack of labelled fields in sections;
 * anything richer should still be written by hand.
 */

/**
 * A titled group of form fields.
 *
 * Pass [title] as null for an untitled leading group. The section owns its
 * outer padding and the vertical gap between children, so callers put fields
 * straight inside without spacers.
 */
@Composable
fun FormSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        content()
    }
}

/**
 * A labelled text field that fills its width.
 *
 * The default [modifier] is `fillMaxWidth`, which is what every form field
 * wanted and had to spell out; pass a modifier to override it. This collapses
 * the repeated `OutlinedTextField(...) + Spacer(height)` pairs down to one call
 * inside a [FormSection].
 */
@Composable
fun LabeledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    isError: Boolean = false,
    supportingText: String? = null,
    placeholder: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText?.let { { Text(it) } },
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = singleLine,
    )
}

/**
 * A repeatable list of typed detail rows, each with a value field, a type
 * dropdown and a remove button, plus an "add" affordance.
 *
 * This is the generic version of `contacts:DetailsSection` — promoted here so
 * contact-like editors can share the shape without copying the function.
 * Callers supply how to read/write the row ([value]/[typeLabel]/[isCustom])
 * and how to create or mutate it ([onValueChange]/[onTypeChange]/[onLabelChange]
 * /[onAdd]/[onRemove]); [Spacing] and the library primitives
 * ([OutlinedTextField]/[DropdownMenu]/[IconRemoveCircle]/[IconAdd]) are reused
 * so the look stays consistent with [FormSection]/[LabeledTextField].
 *
 * [optionLabel] formats each dropdown option; when null the row's [typeLabel]
 * is not used for options (callers that need localized type names should pass
 * it explicitly, e.g. via `ContactDetail.default<T>().withType(option)`).
 */
@Composable
fun <T> FormDetailGroup(
    items: List<T>,
    label: String,
    addLabel: String,
    typeOptions: List<Int>,
    value: (T) -> String,
    onValueChange: (Int, String) -> Unit,
    typeLabel: (T) -> String,
    onTypeChange: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    isCustom: (T) -> Boolean = { false },
    customLabel: (T) -> String = { "" },
    onLabelChange: ((Int, String) -> Unit)? = null,
    customLabelText: String? = null,
    customPlaceholder: String? = null,
    optionLabel: ((Int) -> String)? = null,
    leadingIcon: (@Composable (T) -> Unit)? = null,
    addIcon: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items.forEachIndexed { index, item ->
            OutlinedTextField(
                value = value(item),
                onValueChange = { v -> onValueChange(index, v) },
                label = { Text(label) },
                visualTransformation = visualTransformation,
                leadingIcon = leadingIcon?.let { { it(item) } },
                trailingIcon = {
                    Row {
                        var expanded by remember { mutableStateOf(false) }
                        TextButton({ expanded = true }) {
                            Text(typeLabel(item))
                            IconArrowDropDown()
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            typeOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(optionLabel?.invoke(option) ?: option.toString()) },
                                    onClick = {
                                        onTypeChange(index, option)
                                        expanded = false
                                    },
                                )
                            }
                        }
                        IconButton(onClick = { onRemove(index) }) {
                            IconRemoveCircle()
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
            if (isCustom(item) && onLabelChange != null) {
                Spacer(Modifier.height(Spacing.xs))
                OutlinedTextField(
                    value = customLabel(item),
                    onValueChange = { v -> onLabelChange(index, v) },
                    label = { Text(customLabelText ?: label) },
                    placeholder = customPlaceholder?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(Spacing.xs))
        }
        FilledTonalButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            addIcon?.invoke() ?: IconAdd()
            Spacer(Modifier.width(Spacing.sm))
            Text(addLabel)
        }
    }
}

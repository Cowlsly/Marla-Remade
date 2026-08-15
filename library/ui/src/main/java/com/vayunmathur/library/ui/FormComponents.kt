package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
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

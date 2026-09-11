package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.AllergyCriticality
import com.vayunmathur.health.data.AllergyEntry
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.ui.components.MedicalStorageNotice
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconWarning
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.SwipeActionsBox
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.itemMotion
import com.vayunmathur.library.util.NavBackStack

/**
 * The allergy list.
 *
 * Ordered with the high-criticality entries first rather than by date, unlike every other log here.
 * This is the one screen someone might hand to a clinician in a hurry, and the anaphylaxis needs to
 * be at the top of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllergiesPage(backStack: NavBackStack<Route>, viewModel: MedicalViewModel) {
    val entries by viewModel.allergies.collectAsState()
    var pendingDelete by remember { mutableStateOf<AllergyEntry?>(null) }

    LaunchedEffect(Unit) { viewModel.importFromHealthConnect() }

    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.delete_allergy_confirm, entry.displayName),
            confirmLabel = stringResource(UiR.string.delete),
            destructive = true,
            onConfirm = {
                viewModel.deleteAllergy(entry)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    LazyListScaffold(
        title = stringResource(R.string.allergies),
        scrollBehavior = appBarScrollBehavior(),
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.startAllergyDraft()
                    backStack.add(Route.EditAllergy())
                }
            ) { IconAdd() }
        },
    ) {
        if (!viewModel.healthConnectAvailable) {
            item { MedicalStorageNotice() }
        }

        if (entries.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.no_allergies),
                    message = stringResource(R.string.no_allergies_message),
                    icon = { IconWarning() },
                )
            }
        }

        items(entries, key = { it.id }) { entry ->
            SwipeActionsBox(
                modifier = itemMotion(),
                enableStartToEnd = false,
                onEndToStart = { pendingDelete = entry },
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        viewModel.startAllergyDraft(entry.id)
                        backStack.add(Route.EditAllergy(entry.id))
                    },
                ) {
                    ListItem(
                        headlineContent = { Text(entry.displayName) },
                        overlineContent = { Text(stringResource(entry.category.labelRes())) },
                        supportingContent = { Text(detailLine(entry.reaction, entry.note)) },
                        leadingContent = {
                            IconWarning(
                                tint = if (entry.criticality == AllergyCriticality.High) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    HealthColors.Medical
                                }
                            )
                        },
                        trailingContent = entry.criticality.labelRes()?.let { res ->
                            { Text(stringResource(res)) }
                        },
                    )
                }
            }
        }
    }
}

internal fun com.vayunmathur.health.data.AllergyCategory.labelRes() = when (this) {
    com.vayunmathur.health.data.AllergyCategory.Medication -> R.string.allergy_category_medication
    com.vayunmathur.health.data.AllergyCategory.Food -> R.string.allergy_category_food
    com.vayunmathur.health.data.AllergyCategory.Environment -> R.string.allergy_category_environment
    com.vayunmathur.health.data.AllergyCategory.Biologic -> R.string.allergy_category_biologic
}

/** Null for [AllergyCriticality.Unknown] — an unknown severity is better shown as nothing. */
internal fun AllergyCriticality.labelRes(): Int? = when (this) {
    AllergyCriticality.Low -> R.string.allergy_criticality_low
    AllergyCriticality.High -> R.string.allergy_criticality_high
    AllergyCriticality.Unknown -> null
}

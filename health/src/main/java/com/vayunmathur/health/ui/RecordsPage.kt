package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconMonitorHeart
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.IconScience
import com.vayunmathur.library.ui.IconVaccine
import com.vayunmathur.library.ui.IconWarning
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack

/**
 * The Records tab: an index of the FHIR-backed logs.
 *
 * A list rather than three bottom-bar tabs because these are all browsed rarely, and because the
 * remaining Health Connect medical resource types — procedures, lab results, visits — slot in as
 * further rows without the navigation changing shape.
 *
 * No top bar: [LazyListScaffold] draws one only when given a title or actions, and a bar carrying
 * nothing but a word already on the tab beneath it is a row of wasted height.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsPage(backStack: NavBackStack<Route>, viewModel: MedicalViewModel) {
    val vaccinations by viewModel.vaccinations.collectAsState()
    val allergies by viewModel.allergies.collectAsState()
    val conditions by viewModel.conditions.collectAsState()
    val labResults by viewModel.labResults.collectAsState()
    val profile by viewModel.profile.collectAsState()

    LaunchedEffect(Unit) { viewModel.importFromHealthConnect() }

    LazyListScaffold(
        scrollBehavior = appBarScrollBehavior(),
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            RecordCategoryRow(
                title = stringResource(R.string.vaccinations),
                entries = vaccinations.map { it.displayName },
                icon = { IconVaccine(tint = HealthColors.Medical) },
                onClick = { backStack.add(Route.Vaccinations) },
            )
        }
        item {
            RecordCategoryRow(
                title = stringResource(R.string.allergies),
                entries = allergies.map { it.displayName },
                icon = { IconWarning(tint = HealthColors.Medical) },
                onClick = { backStack.add(Route.Allergies) },
            )
        }
        item {
            RecordCategoryRow(
                title = stringResource(R.string.conditions),
                entries = conditions.map { it.displayName },
                icon = { IconMonitorHeart(tint = HealthColors.Medical) },
                onClick = { backStack.add(Route.Conditions) },
            )
        }
        item {
            RecordCategoryRow(
                title = stringResource(R.string.lab_results),
                entries = labResults.map { it.displayName },
                icon = { IconScience(tint = HealthColors.Medical) },
                onClick = { backStack.add(Route.LabResults) },
            )
        }
        item {
            // Not a log, so it gets a summary of the two answers rather than a count.
            Card(modifier = Modifier.fillMaxWidth(), onClick = { backStack.add(Route.AboutYou) }) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_you)) },
                    overlineContent = { Text(stringResource(R.string.about_you_overline)) },
                    supportingContent = {
                        Text(
                            detailLine(
                                stringResource(profile.pregnancyStatus.labelRes()),
                                stringResource(profile.smokingStatus.labelRes()),
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = { IconPerson(tint = HealthColors.Medical) },
                    trailingContent = { IconChevronRight() },
                )
            }
        }
    }
}

/**
 * One category, laid out like the rows on the log it opens.
 *
 * Same anatomy as every other record card in the app — overline, headline, supporting line, tinted
 * leading icon — so this screen does not read as a different kind of list. The supporting line
 * previews the first couple of entries in the order the sub-page shows them, which is what makes a
 * three-line row worth having rather than padding it out for the sake of matching.
 */
@Composable
private fun RecordCategoryRow(
    title: String,
    entries: List<String>,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        ListItem(
            headlineContent = { Text(title) },
            overlineContent = {
                Text(pluralStringResource(R.plurals.records_recorded, entries.size, entries.size))
            },
            supportingContent = {
                Text(
                    if (entries.isEmpty()) stringResource(R.string.nothing_recorded)
                    else entries.take(PREVIEW_ENTRIES).joinToString(", "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = icon,
            trailingContent = { IconChevronRight() },
        )
    }
}

/** How many entry names to preview on a category row before the line is just noise. */
private const val PREVIEW_ENTRIES = 2

package com.vayunmathur.education.ui
import androidx.compose.ui.res.pluralStringResource
import com.vayunmathur.education.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DetailLazyColumn
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.education.Route
import com.vayunmathur.education.content.ModuleType
import com.vayunmathur.education.util.CourseActions
import com.vayunmathur.education.util.CourseUiState
import com.vayunmathur.education.util.CourseUnitRow
import com.vayunmathur.education.util.EducationViewModel
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.ui.res.stringResource

/** Binds [EducationViewModel] and the back stack to the stateless [ScholarCourseScreen]. */
@Composable
fun ScholarCoursePage(backStack: NavBackStack<Route>, viewModel: EducationViewModel, courseId: String) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val content = viewModel.content
    val course = content.course(courseId)

    ScholarCourseScreen(
        state = CourseUiState(
            title = course?.title ?: stringResource(R.string.course),
            description = course?.description.orEmpty(),
            available = course != null,
            units = course?.units.orEmpty().map { unit ->
                CourseUnitRow(
                    id = unit.id,
                    title = unit.title,
                    lessonCount = unit.lessons.size,
                    stars = averageStars(content.skillIdsOfUnit(unit), progress),
                    dueEpochDay = viewModel.deadlineFor(ModuleType.UNIT, unit.id)?.dueEpochDay,
                )
            },
            challenge = course?.challenge,
        ),
        actions = object : CourseActions {
            override fun navigateUp() {
                backStack.pop()
            }

            override fun openUnit(unitId: String) {
                backStack.add(Route.UnitScreen(unitId))
            }

            override fun openExercise(exerciseId: String) {
                backStack.add(Route.Quiz(exerciseId))
            }
        },
    )
}

/**
 * A course's units, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScholarCourseScreen(state: CourseUiState, actions: CourseActions) {
    if (!state.available) {
        AppScaffold(
            title = state.title,
            onNavigateBack = { actions.navigateUp() },
        ) { padding ->
            MissingContent(padding, stringResource(R.string.this_course_is_unavailable))
        }
        return
    }
    DetailLazyColumn(
        title = state.title,
        onNavigateBack = { actions.navigateUp() },
    ) {
        if (state.description.isNotBlank()) {
            item {
                Text(
                    state.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.units, key = { it.id }) { unit ->
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { actions.openUnit(unit.id) },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(unit.title, style = MaterialTheme.typography.titleMedium)
                        StarRow(unit.stars)
                    }
                    Text(
                        pluralStringResource(R.plurals.lessons, unit.lessonCount, unit.lessonCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    unit.dueEpochDay?.let {
                        Row(Modifier.padding(top = 8.dp)) { DeadlineChip(it) }
                    }
                }
            }
        }
        state.challenge?.let { challenge ->
            item {
                FilledTonalButton(
                    onClick = { actions.openExercise(challenge.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(challenge.title.ifBlank { stringResource(R.string.course_challenge) })
                }
            }
        }
    }
}

@Composable
fun MissingContent(padding: androidx.compose.foundation.layout.PaddingValues, message: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

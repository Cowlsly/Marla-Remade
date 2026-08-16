package com.vayunmathur.education.ui
import androidx.compose.ui.res.pluralStringResource
import com.vayunmathur.education.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.DetailLazyColumn
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedCard
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.education.Route
import com.vayunmathur.education.content.ModuleType
import com.vayunmathur.education.util.EducationViewModel
import com.vayunmathur.education.util.HomeActions
import com.vayunmathur.education.util.HomeCourse
import com.vayunmathur.education.util.HomeDeadline
import com.vayunmathur.education.util.HomeSection
import com.vayunmathur.education.util.HomeUiState
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconEmojiEvents
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.ui.res.stringResource

/** Binds [EducationViewModel] and the back stack to the stateless [ScholarHomeScreen]. */
@Composable
fun ScholarHomePage(backStack: NavBackStack<Route>, viewModel: EducationViewModel) {
    val learner by viewModel.learner.collectAsStateWithLifecycle()
    val deadlines by viewModel.deadlines.collectAsStateWithLifecycle()
    val l = learner ?: return
    val content = viewModel.content
    // Bound out here so the override below is unambiguously the free function, not itself.
    val onParentArea = { openParentArea(backStack, viewModel) }

    ScholarHomeScreen(
        state = HomeUiState(
            learnerName = l.name,
            streakCount = l.streakCount,
            totalStars = l.totalStars,
            deadlines = deadlines.map { d ->
                val type = runCatching { ModuleType.valueOf(d.moduleType) }.getOrNull()
                HomeDeadline(
                    id = d.id,
                    title = type?.let { content.moduleTitle(it, d.moduleId) } ?: "Assignment",
                    dueEpochDay = d.dueEpochDay,
                    moduleType = type,
                    moduleId = d.moduleId,
                )
            },
            sections = content.subjects.map { subject ->
                HomeSection(
                    subject = subject,
                    courses = content.coursesForSubject(subject).map {
                        HomeCourse(it.id, it.title, it.units.size)
                    },
                )
            },
        ),
        actions = object : HomeActions {
            override fun openBadges() {
                backStack.add(Route.Badges)
            }

            override fun openParentArea() {
                onParentArea()
            }

            override fun openCourse(courseId: String) {
                backStack.add(Route.Course(courseId))
            }

            override fun openDeadline(deadline: HomeDeadline) {
                navigateToModule(backStack, deadline.moduleType, deadline.moduleId)
            }
        },
    )
}

/**
 * The catalog, with no dependency on the ViewModel or the back stack so it can be rendered
 * from a `@Preview` — see `src/screenshotTest`, which is where the store listing images
 * come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScholarHomeScreen(state: HomeUiState, actions: HomeActions) {
    DetailLazyColumn(
        title = if (state.learnerName.isBlank()) stringResource(R.string.learn)
        else stringResource(R.string.hi_1, state.learnerName),
        actions = {
            IconButton(onClick = { actions.openBadges() }) {
                IconEmojiEvents()
            }
            IconButton(onClick = { actions.openParentArea() }) {
                IconSettings()
            }
        },
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StreakChip(state.streakCount)
                StarsChip(state.totalStars)
            }
        }

        if (state.deadlines.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.due_soon)) }
            items(state.deadlines, key = { it.id }) { d ->
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { actions.openDeadline(d) },
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(d.title, style = MaterialTheme.typography.titleMedium)
                        DeadlineChip(d.dueEpochDay)
                    }
                }
            }
        }

        state.sections.forEach { section ->
            item { SectionHeader(stringResource(section.subject.displayNameRes)) }
            items(section.courses, key = { it.id }) { course ->
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { actions.openCourse(course.id) },
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(course.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                pluralStringResource(R.plurals.units, course.unitCount, course.unitCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconChevronRight()
                    }
                }
            }
        }

        if (state.sections.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.no_content_packs_are_installed_yet),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Navigates to the screen for a module referenced by a deadline. */
fun navigateToModule(backStack: NavBackStack<Route>, type: ModuleType?, moduleId: String) {
    when (type) {
        ModuleType.COURSE -> backStack.add(Route.Course(moduleId))
        ModuleType.UNIT -> backStack.add(Route.UnitScreen(moduleId))
        ModuleType.LESSON -> backStack.add(Route.LessonScreen(moduleId))
        null -> {}
    }
}

/**
 * Opens the parent area. Until a PIN has been set, this goes straight to the
 * parent settings (where the PIN can be created); once a PIN exists the entry
 * is protected by the PIN gate.
 */
fun openParentArea(backStack: NavBackStack<Route>, viewModel: EducationViewModel) {
    val route = if (viewModel.learner.value?.pinHash == null) Route.Parent else Route.ParentGate
    backStack.add(route)
}

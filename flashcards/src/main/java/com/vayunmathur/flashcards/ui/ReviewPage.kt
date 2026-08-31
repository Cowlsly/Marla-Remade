package com.vayunmathur.flashcards.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.R
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.util.FlashcardsViewModel
import com.vayunmathur.flashcards.util.Grade
import com.vayunmathur.flashcards.util.ReviewActions
import com.vayunmathur.flashcards.util.ReviewUiState
import com.vayunmathur.flashcards.util.StudyMode
import com.vayunmathur.flashcards.util.StudyParams
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ButtonDefaults
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.IconVisibilityOff
import com.vayunmathur.library.ui.IconVolumeUp
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.animatedFloat
import com.vayunmathur.library.ui.Motion
import com.vayunmathur.library.util.NavBackStack
import kotlin.math.abs

/** Binds an in-memory review session over [deckId] to the stateless [ReviewScreen]. */
@Composable
fun ReviewPage(
    backStack: NavBackStack<Route>,
    viewModel: FlashcardsViewModel,
    deckId: Long,
    mode: Int = 0,
    count: Int = 20,
    daysAhead: Int = 3,
    tags: List<String> = emptyList(),
) {
    LaunchedEffect(deckId, mode, count, daysAhead, tags) {
        val params = StudyParams(
            mode = StudyMode.entries.getOrElse(mode) { StudyMode.DUE },
            count = count,
            daysAhead = daysAhead,
        )
        viewModel.startSession(deckId, params, tags.toSet())
    }
    val state by viewModel.review.collectAsStateWithLifecycle()

    ReviewScreen(
        state = state,
        actions = object : ReviewActions {
            override fun back() { backStack.pop() }
            override fun grade(grade: Grade) { viewModel.gradeCurrent(grade) }
            override fun undo() { viewModel.undoReview() }
            override fun suspend() { viewModel.suspendCurrentCard() }
            override fun speak(text: String) { viewModel.speak(text) }
        },
    )
}

/**
 * A single review card, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`. [initialRevealed] seeds the
 * answer-visible state so a preview can capture the graded state without tapping.
 */
@Composable
fun ReviewScreen(
    state: ReviewUiState,
    actions: ReviewActions,
    initialRevealed: Boolean = false,
) {
    var revealed by remember { mutableStateOf(initialRevealed) }
    var typed by remember { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(state.front, state.done) {
        revealed = initialRevealed
        typed = TextFieldValue("")
    }

    // Auto-play: speak the front as each card appears, and the back on reveal.
    LaunchedEffect(state.front) {
        if (state.autoPlay && !state.done && state.front.isNotBlank()) actions.speak(state.front)
    }
    LaunchedEffect(revealed) {
        if (revealed && state.autoPlay && state.back.isNotBlank()) actions.speak(state.back)
    }

    AppScaffold(
        title = {
            if (!state.done) {
                Text(
                    stringResource(
                        R.string.review_counts,
                        state.newCount,
                        state.learningCount,
                        state.reviewCount,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
        onNavigateBack = { actions.back() },
        actions = {
            if (!state.done) {
                IconButton(onClick = { actions.speak(if (revealed) state.back else state.front) }) {
                    IconVolumeUp()
                }
                IconButton(onClick = { actions.suspend() }) { IconVisibilityOff() }
            }
            if (state.canUndo) {
                IconButton(onClick = { actions.undo() }) { IconUndo() }
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { paddingValues ->
        if (state.done) {
            EmptyState(
                title = stringResource(R.string.review_done),
                message = stringResource(R.string.review_done_hint),
                modifier = Modifier.fillMaxSize().padding(paddingValues),
            )
            return@AppScaffold
        }

        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            FlipCard(
                front = state.front,
                back = state.back,
                revealed = revealed,
                typeField = state.typeField,
                typeAnswer = state.typeAnswer,
                typed = typed.text,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable { revealed = true }
                    .then(
                        if (revealed) {
                            Modifier.pointerInput(state.front) {
                                var total = androidx.compose.ui.geometry.Offset.Zero
                                detectDragGestures(
                                    onDragStart = { total = androidx.compose.ui.geometry.Offset.Zero },
                                    onDrag = { change, delta -> change.consume(); total += delta },
                                    onDragEnd = {
                                        gradeForSwipe(total.x, total.y)?.let(actions::grade)
                                    },
                                )
                            }
                        } else {
                            Modifier
                        },
                    ),
            )
            if (state.typeField != null && !revealed) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.type_answer)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (revealed) {
                GradeButtons(state = state, onGrade = { actions.grade(it) })
            } else {
                Button(
                    onClick = { revealed = true },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text(stringResource(R.string.show_answer))
                }
            }
        }
    }
}

/** Maps a drag displacement to a grade: left=Again, right=Good, up=Easy, down=Hard. */
private fun gradeForSwipe(dx: Float, dy: Float): Grade? {
    val threshold = 120f
    if (abs(dx) < threshold && abs(dy) < threshold) return null
    return if (abs(dx) > abs(dy)) {
        if (dx < 0) Grade.AGAIN else Grade.GOOD
    } else {
        if (dy < 0) Grade.EASY else Grade.HARD
    }
}

@Composable
private fun FlipCard(
    front: String,
    back: String,
    revealed: Boolean,
    typeField: String?,
    typeAnswer: String?,
    typed: String,
    modifier: Modifier = Modifier,
) {
    // Duration-based, not the scheme's spring: an underdamped spring overshoots 180 and rubber-bands
    // the card back, which on a face turning in 3D reads as a wobble rather than a flip.
    val rotation = animatedFloat(
        target = if (revealed) 180f else 0f,
        spec = Motion.open(400),
    )
    Box(
        modifier = modifier.graphicsLayer {
            rotationY = rotation
            cameraDistance = 12f * density
        },
        contentAlignment = Alignment.Center,
    ) {
        if (rotation <= 90f) {
            CardFace {
                MarkdownContent(
                    text = front,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        } else {
            // Counter-rotate so the back face reads correctly.
            Box(Modifier.graphicsLayer { rotationY = 180f }) {
                CardFace {
                    MarkdownContent(
                        text = front,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (typeField != null && typeAnswer != null) {
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Text(
                            diffAnnotated(typed, typeAnswer),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 20.dp))
                    MarkdownContent(
                        text = back,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

/**
 * Highlights [typed] against [expected] char-by-char via a longest-common-subsequence:
 * matched characters are green, unmatched typed characters red.
 */
private fun diffAnnotated(typed: String, expected: String): AnnotatedString {
    val n = typed.length
    val m = expected.length
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            lcs[i][j] = if (typed[i] == expected[j]) {
                lcs[i + 1][j + 1] + 1
            } else {
                maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
    }
    val matched = BooleanArray(n)
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            typed[i] == expected[j] -> { matched[i] = true; i++; j++ }
            lcs[i + 1][j] >= lcs[i][j + 1] -> i++
            else -> j++
        }
    }
    val green = SpanStyle(color = Color(0xFF2E7D32))
    val red = SpanStyle(color = Color(0xFFC62828))
    return buildAnnotatedString {
        if (typed.isEmpty()) {
            withStyle(red) { append("—") }
        }
        typed.forEachIndexed { index, c ->
            withStyle(if (matched[index]) green else red) { append(c.toString()) }
        }
    }
}

@Composable
private fun CardFace(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

@Composable
private fun GradeButtons(state: ReviewUiState, onGrade: (Grade) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GradeButton(R.string.grade_again, GRADE_AGAIN_COLOR, state.label(Grade.AGAIN), Grade.AGAIN, onGrade, Modifier.weight(1f))
        GradeButton(R.string.grade_hard, GRADE_HARD_COLOR, state.label(Grade.HARD), Grade.HARD, onGrade, Modifier.weight(1f))
        GradeButton(R.string.grade_good, GRADE_GOOD_COLOR, state.label(Grade.GOOD), Grade.GOOD, onGrade, Modifier.weight(1f))
        GradeButton(R.string.grade_easy, GRADE_EASY_COLOR, state.label(Grade.EASY), Grade.EASY, onGrade, Modifier.weight(1f))
    }
}

@Composable
private fun GradeButton(
    labelRes: Int,
    color: Color,
    interval: String,
    grade: Grade,
    onGrade: (Grade) -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = { onGrade(grade) },
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge)
            if (interval.isNotEmpty()) {
                Text(interval, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private val GRADE_AGAIN_COLOR = Color(0xFFD32F2F)
private val GRADE_HARD_COLOR = Color(0xFFF57C00)
private val GRADE_GOOD_COLOR = Color(0xFF388E3C)
private val GRADE_EASY_COLOR = Color(0xFF1976D2)

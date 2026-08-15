package com.vayunmathur.games.wordmaker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.wordmaker.R
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.data.GameMode
import com.vayunmathur.games.wordmaker.platform.WordGameActions
import com.vayunmathur.games.wordmaker.platform.WordGameUiState
import com.vayunmathur.games.wordmaker.ui.components.AnimatedLetter
import com.vayunmathur.games.wordmaker.ui.components.SurfaceText
import com.vayunmathur.games.wordmaker.ui.components.WordToAnimate
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilledIconButton
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.games.wordmaker.ui.components.CompetitiveStatusBar
import com.vayunmathur.games.wordmaker.ui.components.CrosswordBoard
import com.vayunmathur.games.wordmaker.ui.components.DailyStatusBar
import com.vayunmathur.games.wordmaker.ui.components.LetterChooser
import com.vayunmathur.games.wordmaker.ui.components.WordMakerTopBar
import com.vayunmathur.games.wordmaker.ui.dialogs.BonusWordsDialog
import com.vayunmathur.games.wordmaker.ui.dialogs.DefinitionDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

@Composable
fun WordGameScreen(
    state: WordGameUiState,
    actions: WordGameActions,
    onOpenGameCenter: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val crosswordData = state.crosswordData
    val currentLevel = state.currentLevel
    val foundWords = state.foundWords
    val bonusWords = state.bonusWords
    val tapToSpell = state.tapToSpell
    val revealedHints = state.revealedHints
    val hintCooldownEnd = state.hintCooldownEnd
    val gameMode = state.gameMode
    val competitiveScore = state.competitiveScore
    val competitiveLevelNumber = state.competitiveLevelNumber
    val competitiveDeadline = state.competitiveDeadline
    val isCompetitive = gameMode == GameMode.COMPETITIVE
    val isDaily = gameMode == GameMode.DAILY
    val levelKey = when (gameMode) {
        GameMode.COMPETITIVE -> "c$competitiveLevelNumber"
        GameMode.DAILY -> "d${state.dailyDay}"
        GameMode.CASUAL -> "n$currentLevel"
    }
    var showBonusWordsDialog by remember(levelKey) { mutableStateOf(false) }
    var showHintDialog by remember(levelKey) { mutableStateOf(false) }
    var remainingCooldown by remember { mutableLongStateOf(0L) }
    var remainingTime by remember(levelKey) { mutableLongStateOf(0L) }
    var timedOut by remember(levelKey) { mutableStateOf(false) }
    val density = LocalDensity.current
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    var wordWithDefinition by remember { mutableStateOf<Pair<String, List<String>>?>(null) }

    // Animation state
    val coroutineScope = rememberCoroutineScope()
    var animatedWord by remember(levelKey) { mutableStateOf<String?>(null) }
    val animationProgress = remember(levelKey) { Animatable(0f) }
    var wordBoxOffset by remember(levelKey) { mutableStateOf(Offset.Zero) }
    var bonusButtonOffset by remember(levelKey) { mutableStateOf(Offset.Zero) }
    var crosswordCellPositions by remember(levelKey) {
        mutableStateOf<Map<Pair<Int, Int>, Offset>>(
            emptyMap()
        )
    }
    var letterChooserPositions by remember(levelKey) {
        mutableStateOf<Map<Int, Offset>>(
            emptyMap()
        )
    }
    var wordToAnimate by remember(levelKey) { mutableStateOf<WordToAnimate?>(null) }
    var animatedLetters by remember(levelKey) { mutableStateOf<List<AnimatedLetter>>(emptyList()) }

    // Animatables for shaking (we'll animate them directly when submission fails)
    val wordShakeAnim = remember { Animatable(0f) }
    val bonusShakeAnim = remember { Animatable(0f) }

    var scale by remember { mutableFloatStateOf(1f) }
    var shuffledLetters by remember(crosswordData) {
        mutableStateOf(crosswordData.lettersInChooser.mapIndexed { index, char ->
            ChooserLetter(index, char)
        })
    }


    LaunchedEffect(wordToAnimate) {
        wordToAnimate?.let { animationInfo ->
            val word = animationInfo.word
            val letterPositions = crosswordData.letterPositions[word]?.firstOrNull()
            if (letterPositions != null) {
                val letters = word.mapIndexed { index, char ->
                    val id = animationInfo.letterIds[index]
                    val start = (letterChooserPositions[id] ?: Offset.Zero) - rootOffset
                    val end =
                        (crosswordCellPositions[letterPositions[index]] ?: Offset.Zero) - rootOffset

                    val offsetCorrection = with(density) { 15.dp.toPx() }
                    val correctedStart = start.plus(Offset(offsetCorrection, offsetCorrection))

                    AnimatedLetter(char, correctedStart, end, Animatable(0f))
                }
                animatedLetters = letters

                // Animate
                val jobs = letters.map {
                    launch {
                        it.progress.animateTo(1f, animationSpec = tween(durationMillis = 800))
                    }
                }
                jobs.joinAll()

                // After animation
                actions.addFoundWord(word)
                wordToAnimate = null
                animatedLetters = emptyList()
            }
        }
    }

    val isWon = crosswordData.winsWith(foundWords)

    LaunchedEffect(competitiveDeadline, isCompetitive, isWon, levelKey) {
        if (!isCompetitive || isWon || competitiveDeadline <= 0L) {
            if (competitiveDeadline <= 0L && !isWon) remainingTime = 0L
            return@LaunchedEffect
        }
        while (true) {
            remainingTime = (competitiveDeadline - System.currentTimeMillis()).coerceAtLeast(0)
            if (remainingTime <= 0L) break
            delay(200)
        }
        if (!isWon) {
            timedOut = true
            actions.onCompetitiveTimeout()
        }
    }

    LaunchedEffect(hintCooldownEnd) {
        while (true) {
            remainingCooldown = (hintCooldownEnd - System.currentTimeMillis()).coerceAtLeast(0)
            if (remainingCooldown <= 0) break
            delay(100)
        }
    }

    Scaffold(
        Modifier.fillMaxSize(),
        topBar = {
            WordMakerTopBar(
                gameMode = gameMode,
                onModeSelected = { actions.setGameMode(it) },
                onOpenGameCenter = onOpenGameCenter,
                onOpenSettings = onOpenSettings,
                levelNumber = currentLevel
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding).padding(bottom = 32.dp)
                .fillMaxSize()
                .onGloballyPositioned {
                    rootOffset = it.localToRoot(Offset.Zero)
                }
        ) {
            // Puzzle board fills entire area so it can be dragged under the letter wheel.
            // It is drawn first (behind), so the wheel and buttons appear on top.
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CrosswordBoard(
                    foundWords = foundWords,
                    revealedHints = revealedHints,
                    crosswordData = crosswordData,
                    wordToAnimate = wordToAnimate?.word,
                    onCellPositioned = { position, offset ->
                        if (crosswordCellPositions[position] != offset) {
                            crosswordCellPositions =
                                crosswordCellPositions + (position to offset)
                        }
                    },
                    onCellClicked = { row, col ->
                        val word = crosswordData.getWordAt(row, col, foundWords)
                        if (word != null && word in foundWords) {
                            val definition = actions.getDefinition(word)
                            if (definition.isNotEmpty()) {
                                wordWithDefinition = Pair(word, definition)
                            }
                        }
                    }, {
                        scale = it
                    }
                )
            }
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isCompetitive) {
                    CompetitiveStatusBar(
                        score = competitiveScore,
                        remainingTimeMs = remainingTime
                    )
                } else if (isDaily) {
                    DailyStatusBar(streak = state.dailyStreak)
                }
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier.height(320.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isWon && isDaily) {
                        Text(
                            stringResource(R.string.daily_come_back_tomorrow),
                            fontWeight = FontWeight.Bold
                        )
                    } else if (isWon && !isCompetitive) {
                        Button(onClick = { actions.saveLevel(currentLevel + 1) }) {
                            Text(stringResource(R.string.next_level))
                        }
                    } else if (isCompetitive && (isWon || timedOut)) {
                        // Level finished ΓÇö the between-levels lobby (WordMakerGameLoader) takes over.
                    } else {
                        LetterChooser(
                            letters = shuffledLetters,
                            tapToSpell = tapToSpell,
                            onShuffle = {
                                var nextLetters = shuffledLetters.shuffled()
                                while (nextLetters == shuffledLetters && shuffledLetters.size > 1) {
                                    nextLetters = shuffledLetters.shuffled()
                                }
                                shuffledLetters = nextLetters
                            },
                            onWordSubmitted = { word, ids ->
                                suspend fun shakeAnim(anim: Animatable<Float, AnimationVector1D>, duration: Int = 40) {
                                    for (o in listOf(-16f, 12f, -8f, 6f, -3f, 0f)) {
                                        anim.animateTo(with(density) { o.dp.toPx() }, tween(duration))
                                    }
                                }

                                val isSolution = word in crosswordData.solutionWords
                                val isBonus = !isSolution && word.length >= 3 && actions.isInDictionary(word)

                                when {
                                    isSolution && word !in foundWords -> {
                                        wordToAnimate = WordToAnimate(word, ids)
                                        actions.onSolutionWordFound(word)
                                    }
                                    isBonus && word !in bonusWords -> {
                                        coroutineScope.launch {
                                            animatedWord = word
                                            animationProgress.snapTo(0f)
                                            animationProgress.animateTo(1f, tween(800))
                                            actions.addBonusWord(word)
                                            animatedWord = null
                                        }
                                    }
                                    isBonus && word in bonusWords -> {
                                        val j = launch { shakeAnim(bonusShakeAnim, 60) }
                                        shakeAnim(wordShakeAnim)
                                        j.join()
                                    }
                                    else -> shakeAnim(wordShakeAnim)
                                }
                            },
                            onWordBoxPositioned = { wordBoxOffset = it },
                            onLetterPositioned = { id, offset ->
                                if (letterChooserPositions[id] != offset) {
                                    letterChooserPositions =
                                        letterChooserPositions + (id to offset)
                                }
                            },
                            wordShakeTranslation = wordShakeAnim.value
                        )
                    }
                }
            }

            FilledIconButton(
                onClick = { showBonusWordsDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .padding(bottom = 32.dp)
                    .onGloballyPositioned { bonusButtonOffset = it.localToRoot(Offset.Zero) }
                    .graphicsLayer {
                        translationX = bonusShakeAnim.value
                    },
                enabled = bonusWords.isNotEmpty()
            ) {
                Icon(painterResource(R.drawable.outline_book_2_24), null)
            }

            if (!isWon && !isCompetitive) {
                val hintEnabled = remainingCooldown <= 0L
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    FilledIconButton(
                        onClick = { showHintDialog = true },
                        enabled = hintEnabled
                    ) {
                        Icon(
                            painterResource(android.R.drawable.ic_menu_help),
                            contentDescription = stringResource(R.string.cd_hint),
                            modifier = Modifier.graphicsLayer { alpha = if (hintEnabled) 1f else 0.5f }
                        )
                    }
                    if (!hintEnabled) {
                        CircularProgressIndicator(
                            progress = { 1f - (remainingCooldown / 30_000f) },
                            modifier = Modifier.size(48.dp).align(Alignment.Center),
                            strokeWidth = 3.dp
                        )
                    }
                }
            }

            if (showHintDialog) {
                AlertDialog(
                    onDismissRequest = { showHintDialog = false },
                    title = { Text(stringResource(R.string.hint_confirmation)) },
                    confirmButton = {
                        Button(onClick = {
                            actions.revealHint(crosswordData, foundWords, revealedHints)
                            showHintDialog = false
                        }) {
                            Text(stringResource(R.string.yes))
                        }
                    },
                    dismissButton = {
                        Button(onClick = { showHintDialog = false }) {
                            Text(stringResource(R.string.no))
                        }
                    }
                )
            }

            if (showBonusWordsDialog) {
                BonusWordsDialog(bonusWords = bonusWords, getDefinition = actions::getDefinition) {
                    showBonusWordsDialog = false
                }
            }

            wordWithDefinition?.let { (word, definition) ->
                DefinitionDialog(word, definition) {
                    wordWithDefinition = null
                }
            }

            animatedWord?.let { word ->
                val progress = animationProgress.value
                val currentOffset = lerp(wordBoxOffset, bonusButtonOffset, progress)
                val alpha = 1f - progress
                val scale = 1f - (progress * 0.5f)

                Text(
                    text = word,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    modifier = Modifier
                        .offset { IntOffset(currentOffset.x.toInt(), currentOffset.y.toInt()) }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            alpha = alpha
                        )
                )
            }
            val (size, fontSize) = Pair(35.dp * scale, 18.sp * scale)
            animatedLetters.forEach { letter ->
                val progress = letter.progress.value
                val offset = lerp(letter.startOffset, letter.endOffset, progress)

                SurfaceText(Modifier.offset { IntOffset(offset.x.toInt(), offset.y.toInt()) },
                    RoundedCornerShape(4.dp * scale),
                    MaterialTheme.colorScheme.primary, letter.char.toString(),
                    Modifier, FontWeight.Bold, fontSize, size,
                    textColor = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

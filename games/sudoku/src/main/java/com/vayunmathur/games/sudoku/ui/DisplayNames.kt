package com.vayunmathur.games.sudoku.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.sudoku.R
import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty

/**
 * Display names live here rather than on the enums so `data` stays free of Android types and the
 * enums can be used from the generator and its unit tests without a resource lookup.
 */
@Composable
fun BoardSize.displayName(): String = stringResource(
    when (this) {
        BoardSize.SIX -> R.string.size_six
        BoardSize.NINE -> R.string.size_nine
        BoardSize.TWELVE -> R.string.size_twelve
    }
)

@Composable
fun Difficulty.displayName(): String = stringResource(
    when (this) {
        Difficulty.EASY -> R.string.difficulty_easy
        Difficulty.MEDIUM -> R.string.difficulty_medium
        Difficulty.HARD -> R.string.difficulty_hard
        Difficulty.EXPERT -> R.string.difficulty_expert
    }
)

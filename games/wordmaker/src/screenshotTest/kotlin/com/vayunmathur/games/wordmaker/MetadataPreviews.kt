package com.vayunmathur.games.wordmaker

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.data.Difficulty
import com.vayunmathur.games.wordmaker.data.GameMode
import com.vayunmathur.games.wordmaker.platform.CompetitiveLobbyActions
import com.vayunmathur.games.wordmaker.platform.CompetitiveLobbyUiState
import com.vayunmathur.games.wordmaker.platform.CompetitiveResult
import com.vayunmathur.games.wordmaker.platform.WordGameActions
import com.vayunmathur.games.wordmaker.platform.WordGameUiState
import com.vayunmathur.library.ui.DynamicTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Builds a board from its grid the same way the app does, so the derived parts — solution
 * words, letter-wheel contents, cell coordinates — are guaranteed consistent with the rows
 * below instead of hand-transcribed. `.` is an empty cell, exactly as in the level assets.
 * [CrosswordData.fromString] is pure string parsing, so it is safe inside a preview.
 */
private fun crossword(vararg rows: String): CrosswordData =
    CrosswordData.fromString(rows.joinToString("\n"))!!

/** `assets/levels/101.txt` — VERIFY and its seven shorter words. */
private val LEVEL_101 = crossword(
    "...FIVE.",
    ".F.I.E..",
    "RIFE.R.R",
    ".R.R.IVY",
    "VERY.F.E",
    ".....Y..",
)

/** `assets/levels/333.txt` — TOMBOY, big enough to fill the board on the win screen. */
private val LEVEL_333 = crossword(
    "....BOOM.",
    ".M..O..O.",
    "BOOTY.TOO",
    ".O....O..",
    ".TOMBOY..",
    "...O.....",
    "TOMB.BOOT",
)

/**
 * Store listing images for `:games:wordmaker`. See `common-conventions-preview-metadata`.
 *
 * `./gradlew :games:wordmaker:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/games-wordmaker/`, where `release.sh` picks them up.
 *
 * Order comes from the function names — the generated PNG filenames embed them, so
 * `Preview1Puzzle`/`Preview2Solved`/... sort into listing order. Renumber if you reorder.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions, or the engine silently discovers no tests.
 *
 * `hintCooldownEnd` and `competitiveDeadline` are deliberately 0 so the screen's two polling
 * effects fall straight through instead of counting down against the wall clock — a preview
 * has to settle on the first frame.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-puzzle", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Puzzle() {
        DynamicTheme(darkTheme = true) {
            WordGameScreen(
                state = WordGameUiState(
                    crosswordData = LEVEL_101,
                    currentLevel = 101,
                    foundWords = setOf("FIVE", "RIFE", "IVY"),
                    bonusWords = setOf("FIR", "IRE", "REV", "VIE"),
                    revealedHints = setOf(4 to 0),
                ),
                actions = WordGameActions.Noop,
                onOpenGameCenter = {},
                onOpenSettings = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-solved", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Solved() {
        DynamicTheme(darkTheme = true) {
            WordGameScreen(
                state = WordGameUiState(
                    crosswordData = LEVEL_333,
                    currentLevel = 333,
                    // Every solution word found, which is what puts the screen in its win
                    // state: the grid fills in and the wheel gives way to "Next Level".
                    foundWords = LEVEL_333.solutionWords,
                    bonusWords = setOf("BOO", "MOT", "TOM", "YOB"),
                ),
                actions = WordGameActions.Noop,
                onOpenGameCenter = {},
                onOpenSettings = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-competitive", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Competitive() {
        DynamicTheme(darkTheme = true) {
            CompetitiveLobbyScreen(
                state = CompetitiveLobbyUiState(
                    gameMode = GameMode.COMPETITIVE,
                    difficulty = Difficulty.HARD,
                    score = 185,
                    result = CompetitiveResult(won = true, delta = Difficulty.HARD.winDelta),
                ),
                actions = CompetitiveLobbyActions.Noop,
                onOpenGameCenter = {},
                onOpenSettings = {},
            )
        }
    }
}

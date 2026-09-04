package com.vayunmathur.games.chess

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.square
import com.vayunmathur.games.chess.util.ChessActions
import com.vayunmathur.games.chess.util.ChessUiState
import com.vayunmathur.games.chess.util.PuzzleActions
import com.vayunmathur.games.chess.util.PuzzleDifficulty
import com.vayunmathur.games.chess.util.PuzzleStatus
import com.vayunmathur.games.chess.util.PuzzleUiState
import com.vayunmathur.library.ui.DynamicTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:games:chess`. See `common-conventions-preview-metadata`.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 *
 * Positions are reached by replaying real moves through [Board], which is a pure rules model
 * — so the move list, the captured pieces and the check markers are all genuinely derived
 * rather than faked. Crucially none of this reaches the model: the engine is only ever driven
 * from [com.vayunmathur.games.chess.util.ChessViewModel], and these render with
 * [ChessActions.Noop], so no Vulkan device is ever brought up.
 *
 * `NewGameDialog` is the one exception worth naming, because it *is* rendered here: it reads
 * `Difficulty.entries`, which is a top-level enum with no native handle behind it, and takes
 * the availability flag as a parameter that defaults true. Neither reaches
 * [com.vayunmathur.games.chess.util.MaiaEngine], whose initialiser would try to load a `.so`
 * that a JVM screenshot run does not have.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-game", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Game() {
        DynamicTheme(darkTheme = true) {
            ChessGameScreen(
                state = ChessUiState(
                    board = grecoAttack,
                    // The bishop is picked up, so the board shows its move hints and the
                    // capture ring on the knight it is eyeing.
                    selectedPiece = square("c4"),
                    turn = PieceColor.WHITE,
                ),
                actions = ChessActions.Noop,
                onNewGame = {},
                onOpenGameCenter = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-puzzles", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Puzzles() {
        DynamicTheme(darkTheme = true) {
            PuzzleBoardScreen(
                state = PuzzleUiState(
                    board = scholarsMatePuzzle,
                    playerColor = PieceColor.WHITE,
                    rating = 812,
                    solutionIndex = 1,
                    status = PuzzleStatus.Solving,
                    difficulty = PuzzleDifficulty.EASY,
                ),
                actions = PuzzleActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-newgame", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3NewGame() {
        DynamicTheme(darkTheme = true) {
            // What the app opens on: the starting position behind the mode picker.
            ChessGameScreen(
                state = ChessUiState(),
                actions = ChessActions.Noop,
                onNewGame = {},
                onOpenGameCenter = {},
            )
            NewGameDialog(onNewGame = {})
        }
    }

    /**
     * Italian Game, Greco Attack, through 9…Nxd5 — an opening with enough exchanges that both
     * captured-piece rows and the move list have something in them.
     */
    private val grecoAttack: Board = listOf(
        "e2" to "e4", "e7" to "e5",
        "g1" to "f3", "b8" to "c6",
        "f1" to "c4", "f8" to "c5",
        "c2" to "c3", "g8" to "f6",
        "d2" to "d4", "e5" to "d4",
        "c3" to "d4", "c5" to "b4",
        "c1" to "d2", "b4" to "d2",
        "b1" to "d2", "d7" to "d5",
        "e4" to "d5", "f6" to "d5",
    ).fold(Board.initialState) { board, (from, to) -> board.movePiece(square(from), square(to)) }

    /**
     * A Lichess-style puzzle as the app presents one: the stored position plus the opponent's
     * setup move already played, so White is to move and 3…Nf6?? is highlighted as the last
     * move. The solution is Qxf7#.
     */
    private val scholarsMatePuzzle: Board =
        Board.fromFen("rnbqk1nr/pppp1ppp/8/2b1p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 3")
            .movePiece(square("g8"), square("f6"))
}

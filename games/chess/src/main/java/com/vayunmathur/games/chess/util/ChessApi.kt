package com.vayunmathur.games.chess.util

import android.content.Context
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.Move
import com.vayunmathur.games.chess.data.PieceColor

/**
 * The app's one call into the chess engine.
 *
 * A seam rather than a wrapper: everything that knows the engine exists is on this side of it,
 * so [com.vayunmathur.games.chess.util.ChessViewModel] asks for a move and nothing else in the
 * app can reach a model. The store-listing previews render through
 * [ChessActions.Noop] and never construct this, which is what keeps a screenshot run from
 * trying to bring up Vulkan on a JVM.
 */
class ChessApi(context: Context) {

    private val engine = MaiaEngine(context)

    /** Whether the AI can play at all on this device. See [MaiaEngine.isAvailable]. */
    suspend fun isAvailable(): Boolean = engine.isAvailable()

    /**
     * The engine's move, or null when there is none to play.
     *
     * Null means the AI is checkmated or stalemated, or the model is unavailable. Callers must
     * treat it as "no move" rather than as a failure to parse one.
     *
     * [turn] comes from the UI's state, not from [board]: the rules model infers the side to
     * move from its last move, and the puzzle path seeds that with a synthetic king move.
     */
    suspend fun getBestMove(board: Board, turn: PieceColor, difficulty: Difficulty): Move? =
        engine.bestMove(board, turn, difficulty)

    /** Free the model. Idempotent; called from the view model's `onCleared`. */
    fun close() = engine.close()
}

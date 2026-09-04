package com.vayunmathur.games.chess.data

import com.vayunmathur.games.chess.util.MaiaEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The three encodings between the rules model and Maia3, each of which fails silently.
 *
 * A wrong square index, a wrong mirror or a wrong vocabulary entry all produce a legal move
 * that is simply not the one the model chose — the game keeps working and the AI just plays
 * badly. Nothing else in the app can catch that, so it is checked here against values derived
 * by hand.
 *
 * `scripts/ml/maia_parity.py` checks the same encodings against the real model on the real
 * weights. This is the half of it that runs without a 13 MB asset or a GPU.
 */
class MaiaEncodingTest {

    // file 'a'..'h', rank 1..8  ->  internal Position(row, col) where row 0 = rank 8, col 0 = file a
    private fun sq(file: Char, rank: Int) = Position(8 - rank, file - 'a')

    private fun board(vararg placements: Pair<Position, Piece>): Board {
        val grid = MutableList(8) { MutableList<Piece?>(8) { null } }
        for ((pos, piece) in placements) grid[pos.row][pos.col] = piece
        return Board(grid.map { it.toList() })
    }

    /** The model's square index for a plane-major offset: plane `p`, square `s` at `p * 64 + s`. */
    private fun planeAt(planes: FloatArray, plane: Int, square: Int) = planes[plane * 64 + square]

    /** Which `(plane, square)` pairs are set, so a test can assert the whole encoding at once. */
    private fun occupied(planes: FloatArray): Set<Pair<Int, Int>> =
        planes.indices.filter { planes[it] != 0f }.map { it / 64 to it % 64 }.toSet()

    @Test
    fun planes_areIndexedFromA1_withWhitePiecesFirst() {
        // A white rook on a1 (square 0) and a black king on h8 (square 63). Planes are
        // P,N,B,R,Q,K for the mover then the same six for the opponent, so a rook is plane 3
        // and an opposing king plane 11.
        val b = board(
            sq('a', 1) to Piece(PieceType.ROOK, PieceColor.WHITE),
            sq('h', 8) to Piece(PieceType.KING, PieceColor.BLACK),
        )
        val planes = MaiaEngine.encodePlanes(b, PieceColor.WHITE)
        assertEquals(12 * 64, planes.size)
        assertEquals(setOf(3 to 0, 11 to 63), occupied(planes))
    }

    @Test
    fun planes_useTheDatasetPieceOrderAndNotPieceTypesOwn() {
        // `PieceType` is declared KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN; the model wants
        // P, N, B, R, Q, K. Taking the enum's own ordinal would reverse every plane, which is
        // a wrong-but-plausible encoding this pins down.
        val order = listOf(
            PieceType.PAWN, PieceType.KNIGHT, PieceType.BISHOP,
            PieceType.ROOK, PieceType.QUEEN, PieceType.KING,
        )
        order.forEachIndexed { plane, type ->
            val b = board(sq('a', 1) to Piece(type, PieceColor.WHITE))
            assertEquals(1f, planeAt(MaiaEngine.encodePlanes(b, PieceColor.WHITE), plane, 0), "$type")
        }
    }

    @Test
    fun planes_forBlackToMove_mirrorTheRankAndSwapTheColour() {
        // A black rook on a8 with black to move must encode exactly as a white rook on a1 with
        // white to move: the model only ever sees a position from the mover's side.
        val white = board(sq('a', 1) to Piece(PieceType.ROOK, PieceColor.WHITE))
        val black = board(sq('a', 8) to Piece(PieceType.ROOK, PieceColor.BLACK))
        assertTrue(
            MaiaEngine.encodePlanes(white, PieceColor.WHITE)
                .contentEquals(MaiaEngine.encodePlanes(black, PieceColor.BLACK))
        )
    }

    @Test
    fun planes_forBlackToMove_leaveTheFileAlone() {
        // The mirror is vertical only. A piece on b1 must not come back on g8: flipping the
        // file as well is the other plausible mirror, and it is wrong.
        val b = board(sq('b', 1) to Piece(PieceType.KNIGHT, PieceColor.BLACK))
        val planes = MaiaEngine.encodePlanes(b, PieceColor.BLACK)
        // b1 mirrors to b8, which is square 57, and the knight becomes the mover's (plane 1).
        assertEquals(setOf(1 to 57), occupied(planes))
    }

    @Test
    fun planes_ofTheOpeningPosition_areTwoSolidRanksPerSide() {
        val planes = MaiaEngine.encodePlanes(Board.initialState, PieceColor.WHITE)
        assertEquals(32, planes.count { it != 0f })
        // Eight white pawns on rank 2, squares 8..15.
        for (square in 8..15) assertEquals(1f, planeAt(planes, 0, square), "square $square")
        // The white king on e1 is square 4; the black king on e8 is 60, plane 11.
        assertEquals(1f, planeAt(planes, 5, 4))
        assertEquals(1f, planeAt(planes, 11, 60))
    }

    @Test
    fun planes_ofTheOpeningPosition_areTheSameFromEitherSide() {
        // The start is symmetric, so the mover's view of it does not depend on who moves.
        assertTrue(
            MaiaEngine.encodePlanes(Board.initialState, PieceColor.WHITE)
                .contentEquals(MaiaEngine.encodePlanes(Board.initialState, PieceColor.BLACK))
        )
    }

    @Test
    fun moveIndex_forAnOrdinaryMove_isFromTimes64PlusTo() {
        // e2e4: e2 is square 12, e4 is square 28.
        val pawn = Piece(PieceType.PAWN, PieceColor.WHITE)
        val move = Move(sq('e', 2), sq('e', 4), pawn)
        assertEquals(12 * 64 + 28, MaiaEngine.moveIndex(move, PieceColor.WHITE))
    }

    @Test
    fun moveIndex_forBlackToMove_mirrorsBothSquares() {
        // e7e5 played by black is e2e4 from the mover's side, so it must land on the same index.
        val pawn = Piece(PieceType.PAWN, PieceColor.BLACK)
        val move = Move(sq('e', 7), sq('e', 5), pawn)
        assertEquals(12 * 64 + 28, MaiaEngine.moveIndex(move, PieceColor.BLACK))
    }

    @Test
    fun moveIndex_forAPromotion_isFilesAndPieceWithNoRank() {
        // Promotions are always rank 7 to rank 8, so only the files and the piece are encoded:
        // 4096 + fromFile * 32 + toFile * 4 + piece, with piece in q, r, b, n.
        val pawn = Piece(PieceType.PAWN, PieceColor.WHITE)
        Board.PROMOTION_CHOICES.forEachIndexed { piece, type ->
            val move = Move(sq('c', 7), sq('d', 8), pawn, promotedTo = type)
            assertEquals(4096 + 2 * 32 + 3 * 4 + piece, MaiaEngine.moveIndex(move, PieceColor.WHITE))
        }
    }

    @Test
    fun moveIndex_forABlackPromotion_landsInTheSameRange() {
        // The vocabulary has no black promotions at all: c2c1 for black is c7c8 mirrored.
        val pawn = Piece(PieceType.PAWN, PieceColor.BLACK)
        val move = Move(sq('c', 2), sq('c', 1), pawn, promotedTo = PieceType.QUEEN)
        assertEquals(4096 + 2 * 32 + 2 * 4 + 0, MaiaEngine.moveIndex(move, PieceColor.BLACK))
    }

    @Test
    fun moveIndex_isDistinctAcrossTheWholeVocabulary() {
        // Every from-to pair and every promotion must land on its own entry, and all of them
        // must fall inside the 4352 the model emits. A collision would make two moves share a
        // logit, which no single-move test can see.
        val pawn = Piece(PieceType.PAWN, PieceColor.WHITE)
        val seen = mutableSetOf<Int>()
        for (from in 0 until 64) {
            for (to in 0 until 64) {
                val move = Move(position(from), position(to), pawn)
                val index = MaiaEngine.moveIndex(move, PieceColor.WHITE)
                assertTrue(seen.add(index), "duplicate index $index")
                assertTrue(index in 0 until 4096, "index $index outside the from-to range")
            }
        }
        for (fromFile in 0 until 8) {
            for (toFile in 0 until 8) {
                for (type in Board.PROMOTION_CHOICES) {
                    val move = Move(
                        position(48 + fromFile),
                        position(56 + toFile),
                        pawn,
                        promotedTo = type,
                    )
                    val index = MaiaEngine.moveIndex(move, PieceColor.WHITE)
                    assertTrue(seen.add(index), "duplicate promotion index $index")
                    assertTrue(index in 4096 until 4352, "index $index outside the promotion range")
                }
            }
        }
        assertEquals(4352, seen.size)
    }

    /** A model square index back to a [Position]: square `rank * 8 + file`, row 0 = rank 8. */
    private fun position(square: Int) = Position(7 - square / 8, square % 8)

    @Test
    fun legalMoves_ofTheOpeningPosition_areTheTwentyEveryoneKnows() {
        val moves = Board.initialState.legalMoves(PieceColor.WHITE)
        assertEquals(20, moves.size)
        assertNotNull(moves.find { it.start == sq('e', 2) && it.end == sq('e', 4) })
        assertNotNull(moves.find { it.start == sq('g', 1) && it.end == sq('f', 3) })
        // No promotions are available, so nothing carries one.
        assertTrue(moves.all { it.promotedTo == null })
    }

    @Test
    fun legalMoves_expandAPromotionIntoFour() {
        // A white pawn on c7 with the c8 square empty: c7c8 is one step but four moves.
        val b = board(
            sq('c', 7) to Piece(PieceType.PAWN, PieceColor.WHITE),
            sq('e', 1) to Piece(PieceType.KING, PieceColor.WHITE),
            sq('e', 8) to Piece(PieceType.KING, PieceColor.BLACK),
        )
        val promotions = b.legalMoves(PieceColor.WHITE).filter { it.start == sq('c', 7) }
        assertEquals(4, promotions.size)
        assertEquals(Board.PROMOTION_CHOICES.toSet(), promotions.mapNotNull { it.promotedTo }.toSet())
    }

    @Test
    fun legalMoves_andHasLegalMoves_agree() {
        // `hasLegalMoves` is `legalMoves` with an early exit, and mate/stalemate detection
        // rides on it — so the two must not be able to disagree.
        val mate = board(
            sq('a', 8) to Piece(PieceType.KING, PieceColor.BLACK),
            sq('b', 7) to Piece(PieceType.QUEEN, PieceColor.WHITE),
            sq('c', 6) to Piece(PieceType.KING, PieceColor.WHITE),
        )
        assertTrue(mate.legalMoves(PieceColor.BLACK).isEmpty())
        assertTrue(!mate.hasLegalMoves(PieceColor.BLACK))
        assertTrue(Board.initialState.legalMoves(PieceColor.WHITE).isNotEmpty())
        assertTrue(Board.initialState.hasLegalMoves(PieceColor.WHITE))
    }

    @Test
    fun legalMoves_perftToDepthThree() {
        // 20, 400 and 8902 are the standard perft counts from the starting position. They are
        // the check that the generator is the rules and not an approximation of them: a
        // missing castle, a missing en passant or a missing pin all move the depth-3 number.
        fun perft(board: Board, turn: PieceColor, depth: Int): Int {
            val moves = board.legalMoves(turn)
            if (depth == 1) return moves.size
            return moves.sumOf { move ->
                perft(board.movePiece(move.start, move.end, move.promotedTo), turn.opposite, depth - 1)
            }
        }
        assertEquals(20, perft(Board.initialState, PieceColor.WHITE, 1))
        assertEquals(400, perft(Board.initialState, PieceColor.WHITE, 2))
        assertEquals(8902, perft(Board.initialState, PieceColor.WHITE, 3))
    }
}

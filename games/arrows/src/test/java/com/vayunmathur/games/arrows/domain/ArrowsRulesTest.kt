package com.vayunmathur.games.arrows.domain

import com.vayunmathur.games.arrows.data.ArrowPiece
import com.vayunmathur.games.arrows.data.ArrowsGameState
import com.vayunmathur.games.arrows.data.ArrowsPuzzle
import com.vayunmathur.games.arrows.data.Direction
import com.vayunmathur.games.arrows.data.GameMode
import com.vayunmathur.games.arrows.data.Mirror
import com.vayunmathur.games.arrows.data.STARTING_HEARTS
import com.vayunmathur.games.arrows.data.TapOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArrowsRulesTest {

    private val cols = 5
    private val rows = 5

    private fun at(row: Int, col: Int) = row * cols + col

    private fun puzzle(
        pieces: List<ArrowPiece>,
        mirrors: Map<Int, Mirror> = emptyMap(),
    ) = ArrowsPuzzle(cols, rows, pieces, mirrors)

    private fun state(
        puzzle: ArrowsPuzzle,
        removed: Set<Int> = emptySet(),
        hearts: Int = STARTING_HEARTS,
    ) = ArrowsGameState(puzzle, removed, hearts, level = 1, mode = GameMode.CASUAL)

    /** A two-cell arrow whose head is at (row, col) pointing [direction]. */
    private fun arrow(id: Int, row: Int, col: Int, direction: Direction): ArrowPiece {
        val head = at(row, col)
        val tail = at(row - direction.dRow, col - direction.dCol)
        return ArrowPiece(id, listOf(tail, head), direction)
    }

    @Test
    fun exitPathRunsStraightOffTheBoard() {
        val p = puzzle(emptyList())
        // From (2,2) heading right: (2,3), (2,4), then out.
        assertEquals(
            listOf(at(2, 3), at(2, 4)),
            ArrowsRules.exitPath(p, at(2, 2), Direction.RIGHT),
        )
    }

    @Test
    fun exitPathFromTheEdgeIsEmpty() {
        val p = puzzle(emptyList())
        assertEquals(emptyList(), ArrowsRules.exitPath(p, at(2, 4), Direction.RIGHT))
    }

    @Test
    fun aMirrorBendsTheRoute() {
        // A `/` at (2,3) turns a rightward arrow upward, so it leaves through the top.
        val p = puzzle(emptyList(), mapOf(at(2, 3) to Mirror.FORWARD))
        assertEquals(
            listOf(at(2, 3), at(1, 3), at(0, 3)),
            ArrowsRules.exitPath(p, at(2, 2), Direction.RIGHT),
        )
    }

    @Test
    fun backMirrorBendsTheOtherWay() {
        val p = puzzle(emptyList(), mapOf(at(2, 3) to Mirror.BACK))
        assertEquals(
            listOf(at(2, 3), at(3, 3), at(4, 3)),
            ArrowsRules.exitPath(p, at(2, 2), Direction.RIGHT),
        )
    }

    @Test
    fun mirrorsAreTheirOwnInverse() {
        for (mirror in Mirror.entries) {
            for (direction in Direction.entries) {
                assertEquals(
                    direction,
                    mirror.reflect(mirror.reflect(direction)),
                    "${mirror.name} on ${direction.name}",
                )
            }
        }
    }

    @Test
    fun aRingOfMirrorsTrapsTheHeadAndReportsNoExit() {
        // Four mirrors at the corners of a square, oriented to hand the head on clockwise:
        // (1,1) sends UP->RIGHT, (1,3) RIGHT->DOWN, (3,3) DOWN->LEFT, (3,1) LEFT->UP.
        val ring = mapOf(
            at(1, 1) to Mirror.FORWARD,
            at(1, 3) to Mirror.BACK,
            at(3, 3) to Mirror.FORWARD,
            at(3, 1) to Mirror.BACK,
        )
        // Entering the ring along one of its sides, so the head joins the circuit.
        assertNull(ArrowsRules.exitPath(puzzle(emptyList(), ring), at(2, 1), Direction.UP))
    }

    @Test
    fun anUnobstructedArrowClears() {
        val piece = arrow(0, 2, 2, Direction.RIGHT)
        val (next, outcome) = ArrowsRules.tap(state(puzzle(listOf(piece))), 0)
        assertEquals(TapOutcome.CLEARED, outcome)
        assertTrue(next.isWon)
        assertEquals(STARTING_HEARTS, next.hearts, "a clean exit must not cost a heart")
    }

    @Test
    fun anArrowBlockedByAnotherCostsAHeart() {
        val mover = arrow(0, 2, 2, Direction.RIGHT)
        val blocker = arrow(1, 2, 4, Direction.UP)
        val s = state(puzzle(listOf(mover, blocker)))
        val (next, outcome) = ArrowsRules.tap(s, 0)
        assertEquals(TapOutcome.BLOCKED, outcome)
        assertEquals(STARTING_HEARTS - 1, next.hearts)
        assertEquals(0, next.blockedId)
        assertTrue(next.removed.isEmpty(), "a blocked arrow stays on the board")
    }

    @Test
    fun clearingTheBlockerFreesTheArrowBehindIt() {
        val mover = arrow(0, 2, 2, Direction.RIGHT)
        val blocker = arrow(1, 2, 4, Direction.UP)
        val p = puzzle(listOf(mover, blocker))
        assertTrue(ArrowsRules.isBlocked(state(p), mover))
        assertFalse(ArrowsRules.isBlocked(state(p, removed = setOf(1)), mover))
    }

    @Test
    fun anArrowIsNotBlockedByItsOwnBody() {
        // A four-cell arrow heading right, tail trailing behind it: nothing in the way.
        val piece = ArrowPiece(
            id = 0,
            cells = listOf(at(2, 0), at(2, 1), at(2, 2), at(2, 3)),
            direction = Direction.RIGHT,
        )
        assertFalse(ArrowsRules.isBlocked(state(puzzle(listOf(piece))), piece))
    }

    @Test
    fun anArrowTurnedBackIntoItselfIsBlocked() {
        // Head at (2,2) heading right. The mirrors walk it up, back along row 1, then down onto its
        // own tail at (2,1): RIGHT->UP at (2,3), UP->LEFT at (1,3), LEFT->DOWN at (1,1).
        val piece = ArrowPiece(
            id = 0,
            cells = listOf(at(2, 1), at(2, 2)),
            direction = Direction.RIGHT,
        )
        val mirrors = mapOf(
            at(2, 3) to Mirror.FORWARD,
            at(1, 3) to Mirror.BACK,
            at(1, 1) to Mirror.FORWARD,
        )
        val p = puzzle(listOf(piece), mirrors)
        // exitPath is purely geometric - it runs to the edge regardless of what is in the way - so
        // the check is that the route crosses the tail, and that isBlocked acts on it.
        val path = assertNotNull(ArrowsRules.exitPath(p, piece.head, piece.direction))
        assertTrue(at(2, 1) in path, "the route should cross the arrow's own tail: $path")
        assertTrue(ArrowsRules.isBlocked(state(p), piece))
    }

    @Test
    fun tapsOnClearedArrowsAndFinishedBoardsAreIgnored() {
        val piece = arrow(0, 2, 2, Direction.RIGHT)
        val p = puzzle(listOf(piece))
        val (afterWin, _) = ArrowsRules.tap(state(p), 0)
        assertEquals(TapOutcome.IGNORED, ArrowsRules.tap(afterWin, 0).second)

        val failed = state(p, hearts = 0)
        assertEquals(TapOutcome.IGNORED, ArrowsRules.tap(failed, 0).second)
        assertEquals(TapOutcome.IGNORED, ArrowsRules.tap(state(p), 99).second)
    }

    @Test
    fun runningOutOfHeartsFailsTheLevel() {
        val mover = arrow(0, 2, 2, Direction.RIGHT)
        val blocker = arrow(1, 2, 4, Direction.UP)
        var s = state(puzzle(listOf(mover, blocker)), hearts = 1)
        s = ArrowsRules.tap(s, 0).first
        assertTrue(s.isFailed)
        assertFalse(s.isWon)

        val reset = ArrowsRules.resetAfterFailure(s)
        assertEquals(STARTING_HEARTS, reset.hearts)
        assertTrue(reset.removed.isEmpty())
        assertFalse(reset.isFailed)
    }

    @Test
    fun aWonBoardIsNotAlsoAFailure() {
        // Hearts can hit zero on the tap that also finishes the board; winning takes precedence.
        val piece = arrow(0, 2, 2, Direction.RIGHT)
        val s = state(puzzle(listOf(piece)), removed = setOf(0), hearts = 0)
        assertTrue(s.isWon)
        assertFalse(s.isFailed)
    }

    @Test
    fun solveFindsAnOrderForASolvableBoard() {
        val a = arrow(0, 2, 2, Direction.RIGHT)
        val b = arrow(1, 2, 4, Direction.UP)
        val order = assertNotNull(ArrowsRules.solve(puzzle(listOf(a, b))))
        assertEquals(listOf(1, 0), order, "the blocker has to go first")
    }

    @Test
    fun solveReturnsNullWhenTwoArrowsBlockEachOther() {
        // Facing each other across the board, each one's route runs into the other.
        val left = ArrowPiece(0, listOf(at(2, 0), at(2, 1)), Direction.RIGHT)
        val right = ArrowPiece(1, listOf(at(2, 4), at(2, 3)), Direction.LEFT)
        assertNull(ArrowsRules.solve(puzzle(listOf(left, right))))
    }

    @Test
    fun clearableNowListsOnlyTheArrowsThatCanLeave() {
        val mover = arrow(0, 2, 2, Direction.RIGHT)
        val blocker = arrow(1, 2, 4, Direction.UP)
        val free = ArrowsRules.clearableNow(state(puzzle(listOf(mover, blocker))))
        assertEquals(listOf(1), free.map { it.id })
    }
}

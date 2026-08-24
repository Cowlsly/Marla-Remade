package com.vayunmathur.games.arrows.domain

import com.vayunmathur.games.arrows.data.DAILY_ARROWS
import com.vayunmathur.games.arrows.data.DAILY_BOARD
import com.vayunmathur.games.arrows.data.DAILY_MIRRORS
import com.vayunmathur.games.arrows.data.arrowCountForLevel
import com.vayunmathur.games.arrows.data.boardSizeForLevel
import com.vayunmathur.games.arrows.data.mirrorCountForLevel
import com.vayunmathur.games.arrows.data.MIRROR_FIRST_LEVEL
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArrowsGeneratorTest {

    @Test
    fun everyGeneratedBoardCanBeCleared() {
        // The whole promise of reverse construction.
        for (level in 1..40) {
            val puzzle = assertNotNull(generateFor(level), "level $level produced nothing")
            assertNotNull(
                ArrowsRules.solve(puzzle),
                "level $level (${puzzle.cols}x${puzzle.rows}, " +
                    "${puzzle.pieces.size} arrows) has no clearing order",
            )
        }
    }

    @Test
    fun arrowsAreConnectedPolylinesThatDoNotOverlap() {
        for (level in listOf(1, 8, 20, 35)) {
            val puzzle = assertNotNull(generateFor(level))
            val seen = mutableSetOf<Int>()
            for (piece in puzzle.pieces) {
                assertTrue(piece.cells.size >= 2, "level $level arrow ${piece.id} is too short")
                assertEquals(
                    piece.cells.size,
                    piece.cells.distinct().size,
                    "level $level arrow ${piece.id} crosses itself",
                )
                for (cell in piece.cells) {
                    assertTrue(seen.add(cell), "level $level cell $cell used twice")
                }
                // Consecutive cells must be orthogonally adjacent.
                for (i in 0 until piece.cells.size - 1) {
                    val a = piece.cells[i]
                    val b = piece.cells[i + 1]
                    val rowGap = kotlin.math.abs(a / puzzle.cols - b / puzzle.cols)
                    val colGap = kotlin.math.abs(a % puzzle.cols - b % puzzle.cols)
                    assertEquals(1, rowGap + colGap, "level $level arrow ${piece.id} is broken")
                }
            }
        }
    }

    @Test
    fun theArrowheadFollowsTheLastSegment() {
        for (level in listOf(3, 12, 25)) {
            val puzzle = assertNotNull(generateFor(level))
            for (piece in puzzle.pieces) {
                val previous = piece.cells[piece.cells.size - 2]
                val expectedRow = piece.head / puzzle.cols - previous / puzzle.cols
                val expectedCol = piece.head % puzzle.cols - previous % puzzle.cols
                assertEquals(expectedRow, piece.direction.dRow, "arrow ${piece.id} row")
                assertEquals(expectedCol, piece.direction.dCol, "arrow ${piece.id} col")
            }
        }
    }

    @Test
    fun arrowsNeverSitOnAMirror() {
        for (level in listOf(12, 20, 40)) {
            val puzzle = assertNotNull(generateFor(level))
            for (piece in puzzle.pieces) {
                for (cell in piece.cells) {
                    assertTrue(cell !in puzzle.mirrors, "level $level arrow ${piece.id} on a mirror")
                }
            }
        }
    }

    @Test
    fun mirrorsOnlyAppearFromTheirIntroductoryLevel() {
        for (level in 1 until MIRROR_FIRST_LEVEL) {
            assertEquals(0, mirrorCountForLevel(level), "level $level should have no mirrors")
            assertTrue(assertNotNull(generateFor(level)).mirrors.isEmpty(), "level $level")
        }
        assertTrue(mirrorCountForLevel(MIRROR_FIRST_LEVEL) > 0)
    }

    @Test
    fun mirrorsAreNeverOnTheBorder() {
        // A mirror on an edge mostly deflects arrows straight back out, which reads as decoration.
        for (level in listOf(12, 25, 40)) {
            val puzzle = assertNotNull(generateFor(level))
            for (cell in puzzle.mirrors.keys) {
                val row = cell / puzzle.cols
                val col = cell % puzzle.cols
                assertTrue(row in 1 until puzzle.rows - 1, "level $level mirror row $row")
                assertTrue(col in 1 until puzzle.cols - 1, "level $level mirror col $col")
            }
        }
    }

    @Test
    fun sameSeedGivesTheSameBoard() {
        // Saved progress is keyed by level number, so a level must never rebuild itself differently.
        val first = assertNotNull(generateFor(17))
        val second = assertNotNull(generateFor(17))
        assertEquals(first.pieces, second.pieces)
        assertEquals(first.mirrors, second.mirrors)
    }

    @Test
    fun differentLevelsGiveDifferentBoards() {
        val first = assertNotNull(generateFor(5))
        val second = assertNotNull(generateFor(6))
        assertTrue(first.pieces != second.pieces)
    }

    @Test
    fun boardsHoldAWorthwhileNumberOfArrows() {
        for (level in 1..40) {
            val puzzle = assertNotNull(generateFor(level))
            val target = arrowCountForLevel(level)
            assertTrue(
                puzzle.pieces.size >= (target * 2) / 3,
                "level $level wanted ~$target arrows, got ${puzzle.pieces.size}",
            )
        }
    }

    @Test
    fun theDailyBoardIsGeneratedForAnyDay() {
        val (cols, rows) = DAILY_BOARD
        for (day in 20_300L..20_330L) {
            val puzzle = assertNotNull(
                ArrowsGenerator.generateSeeded(
                    cols, rows, DAILY_ARROWS, DAILY_MIRRORS, seed = day,
                ),
                "no daily board for day $day",
            )
            assertNotNull(ArrowsRules.solve(puzzle), "daily $day is unsolvable")
        }
    }

    @Test
    fun generateGivesUpRatherThanLoopingWhenGivenNoAttempts() {
        assertEquals(
            null,
            ArrowsGenerator.generate(5, 5, 4, 0, Random(1), attempts = 0),
        )
    }

    @Test
    fun boardRampNeverShrinks() {
        val areas = (1..60).map { boardSizeForLevel(it).let { (c, r) -> c * r } }
        assertEquals(areas, areas.sorted(), "board area should only ever grow")
    }

    /** Uses the same size, count, mirror and seed rules the game uses for a real level. */
    private fun generateFor(level: Int) = boardSizeForLevel(level).let { (cols, rows) ->
        ArrowsGenerator.generateSeeded(
            cols = cols,
            rows = rows,
            targetPieces = arrowCountForLevel(level),
            mirrorCount = mirrorCountForLevel(level),
            seed = level.toLong(),
        )
    }
}

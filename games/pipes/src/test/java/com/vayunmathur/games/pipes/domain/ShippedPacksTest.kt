package com.vayunmathur.games.pipes.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShippedPacksTest {

    /**
     * A win needs every cell owned (`PipesViewModel.checkWin`), so a level whose pairs can also be
     * joined while leaving a cell empty is a dead end: the player connects everything and nothing
     * happens. That is issue #552, and it is a property of the level rather than of the code, so it
     * has to be asserted against the shipped assets.
     *
     * The assertion is "never provably more than one solution" rather than "always exactly one"
     * because the widest boards can exhaust the solver's state budget and come back
     * [NumberlinkSolver.Verdict.UNDECIDED]. Undecided is not a defect; MULTIPLE and NONE are.
     */
    @Test
    fun noShippedLevelCanBeSolvedWithoutFillingTheBoard() {
        val broken = mutableListOf<String>()
        for (name in PackFixtures.packNames()) {
            for (level in PackFixtures.levels(name)) {
                val verdict = NumberlinkSolver.classify(level.cells, level.endpoints)
                if (verdict == NumberlinkSolver.Verdict.MULTIPLE || verdict == NumberlinkSolver.Verdict.NONE) {
                    broken += "$name/${level.id}=$verdict"
                }
            }
        }
        assertEquals(emptyList(), broken, "shipped levels that do not force a full board")
    }

    /** The narrower boards are well inside the solver's budget, so they must resolve outright. */
    @Test
    fun narrowPacksAreProvablyUnique() {
        for (name in listOf("5x5.json", "6x6.json", "7x7.json", "8x8.json", "9x9.json")) {
            for (level in PackFixtures.levels(name)) {
                assertEquals(
                    NumberlinkSolver.Verdict.UNIQUE,
                    NumberlinkSolver.classify(level.cells, level.endpoints),
                    "$name/${level.id}",
                )
            }
        }
    }

    @Test
    fun thePoolIsBigEnoughToSeedADay() {
        assertTrue(PackFixtures.allLevels().size >= DailyLevels.LEVELS_PER_DAY)
    }
}

package com.vayunmathur.library.ml

import com.vayunmathur.library.ml.Gemma4Handle.Companion.MAX_CONTEXT
import com.vayunmathur.library.ml.Gemma4Handle.Companion.SOFT_TOKEN_WIDTH
import com.vayunmathur.library.ml.Gemma4Handle.Companion.fitAudio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The window budget: how `generate` splits [MAX_CONTEXT] between prompt, audio and reply.
 *
 * The reply used to take whatever the prompt left, via `minOf(limit, remaining - 1)`. That hands
 * a turn every free position, so the turn after it has none and `generate` returns null. These
 * pin the fix: audio is trimmed to leave the reply its [Gemma4Handle.generate] `limit`.
 *
 * Every case below asserts against a budget computed the way `generate` computes it, never
 * against a hard-coded prompt size - the fixed cost is dominated by the tool declarations and
 * has already moved twice while this was being written.
 */
class Gemma4AudioBudgetTest {

    /** What `generate` allows audio, given a fixed prompt cost and a reply reserve. */
    private fun budget(fixed: Int, limit: Int) = MAX_CONTEXT - maxOf(limit, 3) - fixed

    private fun clip(positions: Int) = Gemma4Handle.Part.Audio(FloatArray(positions * SOFT_TOKEN_WIDTH))

    private fun text(positions: Int) = Gemma4Handle.Part.Tokens(IntArray(positions))

    private fun List<Gemma4Handle.Part>.positions() = sumOf { it.positions }

    @Test
    fun `a clip larger than the budget is trimmed to exactly the budget`() {
        val parts = listOf(text(1400), clip(750))
        val allowed = budget(fixed = 1400, limit = 512)
        val fitted = fitAudio(parts, allowed)

        assertTrue(allowed < 750, "the clip has to exceed the budget or nothing is being tested")
        assertEquals(allowed, fitted.filterIsInstance<Gemma4Handle.Part.Audio>().sumOf { it.positions })
        assertTrue(fitted.positions() <= MAX_CONTEXT - 512, "the reply must still have its 512")
    }

    /**
     * The arm that must fail if the trim were removed.
     *
     * Without it the same input overruns the reserve, so a passing test above means the trim did
     * real work rather than that the input happened to fit.
     */
    @Test
    fun `without the trim the same input overruns the reply reserve`() {
        val parts = listOf(text(1400), clip(750))

        assertTrue(
            parts.positions() > MAX_CONTEXT - 512,
            "the control is only meaningful if the untrimmed input genuinely does not fit",
        )
        assertTrue(fitAudio(parts, budget(fixed = 1400, limit = 512)).positions() <= MAX_CONTEXT - 512)
    }

    @Test
    fun `a clip that already fits is passed through untouched`() {
        val parts = listOf(text(340), clip(750))
        val fitted = fitAudio(parts, budget(fixed = 340, limit = 512))

        assertEquals(parts, fitted)
        assertEquals(750, fitted.filterIsInstance<Gemma4Handle.Part.Audio>().single().positions)
    }

    @Test
    fun `a prompt with no audio is returned as the same list`() {
        val parts = listOf(text(1871), text(4))

        assertSame(parts, fitAudio(parts, budget(fixed = 1875, limit = 512)))
        assertSame(parts, fitAudio(parts, budget(fixed = 1875, limit = 4096)))
    }

    @Test
    fun `a budget of nothing drops the clip and keeps the rest`() {
        val fitted = fitAudio(listOf(text(20), clip(12), text(5)), 0)

        assertEquals(listOf(text(20), text(5)), fitted)
    }

    /** At today's prompt the budget is negative, so no clip survives however short it is. */
    @Test
    fun `a negative budget is treated as none rather than wrapping`() {
        val over = budget(fixed = 1871, limit = 512)

        assertTrue(over < 0, "1871 with a 512-token reply genuinely overruns the window")
        assertEquals(emptyList(), fitAudio(listOf(clip(12)), over).filterIsInstance<Gemma4Handle.Part.Audio>())
    }

    @Test
    fun `earlier clips are served before later ones`() {
        val fitted = fitAudio(listOf(clip(10), clip(10)), 15)

        assertEquals(listOf(10, 5), fitted.filterIsInstance<Gemma4Handle.Part.Audio>().map { it.positions })
    }

    /**
     * The budget follows the prefix rather than a constant.
     *
     * `P` has been measured at 1603 and then 1871 in one afternoon, and the shrink work will move
     * it again, so what matters is that a smaller prefix buys audio without anything being edited.
     */
    @Test
    fun `shrinking the fixed prompt is what buys audio, with no constant to update`() {
        assertTrue(budget(fixed = 1871, limit = 512) < 0, "no audio fits today")
        assertTrue(budget(fixed = 340, limit = 512) >= 750, "a full clip fits once the tools go")

        val trimmed = fitAudio(listOf(text(340), clip(750)), budget(340, 512))
        assertEquals(750, trimmed.filterIsInstance<Gemma4Handle.Part.Audio>().single().positions)
    }
}

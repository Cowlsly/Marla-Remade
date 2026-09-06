package com.vayunmathur.openassistant.util

import com.vayunmathur.library.ml.Gemma4Handle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Dropping the oldest exchanges when a conversation outgrows the context window.
 *
 * The prompt is the whole conversation re-fed every turn, against a fixed 2048 positions, so
 * history grows into the same window the reply needs. Past the edge `Gemma4Handle.generate`
 * returns null, and the failure is written back as a message - so without eviction the next
 * prompt is longer than the one that just failed and the conversation cannot recover.
 *
 * `InferenceService.evict` takes the fit test as a parameter precisely so it can be exercised
 * here without a model. The fakes below are what would otherwise need 1.3 GB of weights.
 */
class HistoryEvictionTest {

    private fun user(text: String) = Gemma4Handle.Turn(Gemma4Handle.Role.USER, text)

    private fun model(text: String) = Gemma4Handle.Turn(Gemma4Handle.Role.MODEL, text)

    /** An exchange is a user turn and the reply to it. */
    private fun exchange(n: Int) = listOf(user("q$n"), model("a$n"))

    private fun conversation(count: Int) = (1..count).flatMap { exchange(it) }

    /** Fits when at most [turns] turns remain, which is the shape a position budget has. */
    private fun roomFor(turns: Int): (List<Gemma4Handle.Turn>) -> Boolean = { it.size <= turns }

    @Test
    fun `a conversation that already fits is returned untouched`() {
        val history = conversation(3)

        assertSame(history, InferenceService.evict(history) { true })
    }

    @Test
    fun `the oldest exchange goes first, as a pair`() {
        val kept = InferenceService.evict(conversation(3), fits = roomFor(4))

        assertEquals(listOf("q2", "a2", "q3", "a3"), kept.map { it.text })
    }

    @Test
    fun `exchanges keep going until what remains fits`() {
        val kept = InferenceService.evict(conversation(5), fits = roomFor(2))

        assertEquals(listOf("q5", "a5"), kept.map { it.text })
    }

    /**
     * The control: the predicate must be capable of rejecting, or every test above passes for
     * the wrong reason. A fit test that always accepts would leave history untouched and look
     * exactly like correct behaviour.
     */
    @Test
    fun `the fit test used above genuinely rejects an over-long history`() {
        val history = conversation(5)

        assertTrue(!roomFor(2)(history), "roomFor(2) must reject 10 turns or the tests prove nothing")
        assertTrue(roomFor(2)(history.takeLast(2)), "and must accept once it is short enough")
    }

    /**
     * A model turn with no user turn before it is what a split pair leaves behind. It goes on
     * its own, otherwise the pair rule would take the following user turn with it and evict a
     * question the model still needs.
     */
    @Test
    fun `a leading model turn is dropped on its own`() {
        val history = listOf(model("orphan")) + exchange(1) + exchange(2)

        assertEquals(listOf("q1", "a1", "q2", "a2"), InferenceService.evict(history, fits = roomFor(4)).map { it.text })
    }

    @Test
    fun `a history that never fits empties rather than looping forever`() {
        assertEquals(emptyList(), InferenceService.evict(conversation(4)) { false })
    }

    /**
     * The floor bounds what one turn's attachment may cost the conversation.
     *
     * A thirty-second clip is 750 positions and would otherwise erase everything to seat itself,
     * so the exchange the user is in the middle of survives even though the fit test still says
     * no.
     */
    @Test
    fun `the floor stops a clip evicting the whole conversation`() {
        val kept = InferenceService.evict(conversation(4), InferenceService.HISTORY_FLOOR) { false }

        assertEquals(InferenceService.HISTORY_FLOOR, kept.size)
        assertEquals(listOf("q4", "a4"), kept.map { it.text }, "and it is the most recent exchange")
    }

    /** The floor is for displacing history for audio only; fitting at all overrides it. */
    @Test
    fun `the floorless pass can still empty a history the floor preserved`() {
        val held = InferenceService.evict(conversation(4), InferenceService.HISTORY_FLOOR) { false }

        assertTrue(held.isNotEmpty(), "the floor held it")
        assertEquals(emptyList(), InferenceService.evict(held) { false }, "the second pass does not")
    }

    @Test
    fun `a floor larger than the history keeps all of it`() {
        val history = conversation(1)

        assertSame(history, InferenceService.evict(history, floor = 10) { false })
    }

    /** Nothing to drop is not an error: `generate` refuses it afterwards exactly as it does now. */
    @Test
    fun `an empty history is left alone even when it does not fit`() {
        assertEquals(emptyList(), InferenceService.evict(emptyList()) { false })
    }

    @Test
    fun `an odd trailing turn does not stall the loop`() {
        val history = exchange(1) + exchange(2) + listOf(user("q3"))

        assertEquals(listOf("q3"), InferenceService.evict(history, fits = roomFor(1)).map { it.text })
    }

    /**
     * The reply reserve the eviction measures against, from the one place that defines it.
     *
     * The guard's bound and the reply reserve compete for the same tail of the window rather
     * than stacking, so the ceiling is the larger of the two subtractions and not their sum.
     */
    @Test
    fun `the prompt ceiling leaves the reply exactly its limit`() {
        assertEquals(2048 - 512, Gemma4Handle.promptCeiling(512))
        assertEquals(2048 - 3, Gemma4Handle.promptCeiling(0), "a tiny limit still clears the guard")
        assertTrue(
            Gemma4Handle.promptCeiling(Gemma4Handle.DEFAULT_REPLY) < Gemma4Handle.MAX_CONTEXT,
            "the ceiling must be strictly under the window or nothing is reserved",
        )
    }
}

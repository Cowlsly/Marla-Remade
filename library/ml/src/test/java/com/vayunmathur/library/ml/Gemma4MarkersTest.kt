package com.vayunmathur.library.ml

import com.vayunmathur.library.ml.Gemma4Handle.Companion.BOA_MARKER
import com.vayunmathur.library.ml.Gemma4Handle.Companion.BOI_MARKER
import com.vayunmathur.library.ml.Gemma4Handle.Companion.EOA_MARKER
import com.vayunmathur.library.ml.Gemma4Handle.Companion.EOI_MARKER
import com.vayunmathur.library.ml.Gemma4Handle.Companion.MARKERS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every `*_MARKER` constant must be declared in `MARKERS`.
 *
 * `<|audio>` and `<audio|>` were emitted by `renderParts` for some hours while absent from
 * `MARKERS`, so they encoded as literal characters instead of `boa` 256000 and `eoa` 258883. The
 * decoder was handed delimiters it was not trained on, and nothing anywhere failed: the prompt
 * tokenised, the context guard passed, the soft tokens landed at the right positions, the turn
 * ran and a reply came back. A JNI symbol audit, three Gradle targets, device parity at 0.999997
 * and a GPU run of the tower were all green throughout, because none of them look at the prompt
 * the decoder actually receives.
 *
 * The array had been correct when it was written - its own docstring said "plus the two image
 * brackets" - and became wrong when a later marker was added beside it. So the defect was never
 * that someone chose wrong; it was that nothing connected a constant to the array. This is that
 * connection.
 */
class Gemma4MarkersTest {

    /**
     * The `*_MARKER` constants, found by reflection rather than listed.
     *
     * Listing them here would reproduce the original defect one file over: a new constant, a
     * forgotten list, and a green test. `const val` in a companion compiles to a static field on
     * the containing class, so a constant added tomorrow appears here without anyone editing
     * this.
     */
    private fun markerConstants(): Map<String, String> =
        Gemma4Handle::class.java.declaredFields
            .filter { it.name.endsWith("_MARKER") }
            .onEach { it.isAccessible = true }
            .associate { it.name to (it.get(null) as String) }

    @Test
    fun `the reflection finds the marker constants at all`() {
        // Without this the suite below is vacuous: if `declaredFields` stops returning the
        // constants - a Kotlin change, R8, a move to an object - `markerConstants()` returns
        // empty, every marker is trivially present in MARKERS, and the test passes while
        // checking nothing. It is the same shape as the two vacuous passes we shipped today, and
        // a marker test that cannot fail is worse than none because it is believed.
        val found = markerConstants()
        assertTrue(
            found.size >= 4,
            "reflection found ${found.size} *_MARKER constants, expected at least the four " +
                "image and audio brackets - the enforcement below is vacuous: $found",
        )
        for (name in listOf("BOI_MARKER", "EOI_MARKER", "BOA_MARKER", "EOA_MARKER")) {
            assertTrue(name in found, "$name was not found by reflection; found ${found.keys}")
        }
    }

    @Test
    fun `every marker constant is declared in MARKERS`() {
        val declared = MARKERS.toSet()
        val missing = markerConstants().filterValues { it !in declared }
        assertEquals(
            emptyMap(),
            missing,
            "these *_MARKER constants are not in MARKERS, so they encode as literal characters " +
                "rather than their own token ids: $missing",
        )
    }

    @Test
    fun `the four bracket constants hold the strings the checkpoint uses`() {
        // Pinned against config.json's boi/eoi/boa/eoa ids, so a typo in a constant fails here
        // rather than becoming an unrecognised delimiter at inference. The ids are 255999,
        // 258882, 256000 and 258883 respectively.
        assertEquals("<|image>", BOI_MARKER)
        assertEquals("<image|>", EOI_MARKER)
        assertEquals("<|audio>", BOA_MARKER)
        assertEquals("<audio|>", EOA_MARKER)
    }

    @Test
    fun `MARKERS has no duplicates`() {
        val duplicated = MARKERS.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertEquals(emptyMap(), duplicated, "duplicated markers: $duplicated")
    }
}

package com.vayunmathur.tuner.domain

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Concert pitch, and the only reference the app uses.
 *
 * There is no UI to change it: the picker that used to sit on the Note tab was noise for
 * everyone who tunes to A = 440. [PitchReference] still takes the reference as a parameter and
 * the bounds below are still here, so reintroducing a picker is a UI change and nothing more.
 */
const val DEFAULT_A4_HZ: Double = 440.0

/** Lowest sensible reference, kept for a future picker: baroque A = 415. */
const val MIN_A4_HZ: Double = 415.0

/** Highest sensible reference, kept for a future picker: A = 443 with headroom. */
const val MAX_A4_HZ: Double = 466.0

private const val LN_2 = 0.6931471805599453

internal fun log2(x: Double): Double = ln(x) / LN_2

/** How accidentals are spelled in the note readout. */
enum class AccidentalStyle { SHARPS, FLATS }

private val SHARP_NAMES =
    listOf("C", "C\u266F", "D", "D\u266F", "E", "F", "F\u266F", "G", "G\u266F", "A", "A\u266F", "B")
private val FLAT_NAMES =
    listOf("C", "D\u266D", "D", "E\u266D", "E", "F", "G\u266D", "G", "A\u266D", "A", "B\u266D", "B")

/**
 * A note name in scientific pitch notation - MIDI 60 is `C4`, MIDI 69 is `A4`.
 *
 * The letter is not a translatable string: pitch letters are the same in every locale this app
 * ships in, and solfege/German naming is a deliberate v1 omission (TUNER_SPEC B.4).
 */
data class NoteSpelling(val letter: String, val octave: Int) {
    /** e.g. `"E2"`. */
    override fun toString(): String = "$letter$octave"
}

/**
 * The 12-TET reference frame: an A4 frequency plus optional per-pitch-class cent offsets.
 *
 * All arithmetic is `Double`. At 30 Hz one cent is a relative change of 5.8e-4, which leaves
 * `Float` about four significant digits of headroom on the log - not enough to state a
 * sub-cent result honestly.
 *
 * [temperament] is carried so that stretched piano tuning or a historical temperament is a data
 * change rather than a refactor. v1 always passes all-zero.
 */
class PitchReference(
    val a4Hz: Double = DEFAULT_A4_HZ,
    private val temperament: DoubleArray = DoubleArray(12),
) {
    init {
        require(temperament.size == 12) { "temperament must have 12 entries" }
    }

    /** The reference frequency of [midi] under this tuning. */
    fun referenceHz(midi: Int): Double {
        val equal = a4Hz * 2.0.pow((midi - 69) / 12.0)
        val offset = temperament[pitchClassOf(midi)]
        return if (offset == 0.0) equal else equal * 2.0.pow(offset / 1200.0)
    }

    /** The MIDI number whose reference frequency is closest to [hz], in cents. */
    fun nearestMidi(hz: Double): Int = (69.0 + 12.0 * log2(hz / a4Hz)).roundToInt()

    /** Signed deviation of [hz] from [midi]'s reference, in cents. */
    fun centsFrom(hz: Double, midi: Int): Double = 1200.0 * log2(hz / referenceHz(midi))
}

/** `midi mod 12`, correct for negative input. */
fun pitchClassOf(midi: Int): Int = ((midi % 12) + 12) % 12

/** Spells [midi] with the requested [style]. */
fun spell(midi: Int, style: AccidentalStyle = AccidentalStyle.SHARPS): NoteSpelling {
    val names = if (style == AccidentalStyle.FLATS) FLAT_NAMES else SHARP_NAMES
    return NoteSpelling(names[pitchClassOf(midi)], Math.floorDiv(midi, 12) - 1)
}

/** Spells a bare pitch class (0 = C), for chord roots where the octave is not meaningful. */
fun spellPitchClass(pitchClass: Int, style: AccidentalStyle = AccidentalStyle.SHARPS): String {
    val names = if (style == AccidentalStyle.FLATS) FLAT_NAMES else SHARP_NAMES
    return names[pitchClassOf(pitchClass)]
}

/** How close to the target a reading is, for colouring the needle (TUNER_SPEC B.5). */
enum class TuningBand { IN_TUNE, CLOSE, OUT }

/**
 * Bands the deviation. The green band is +/-1 cent rather than something tighter because +/-1
 * cent is roughly the end-to-end accuracy of the whole system (TUNER_SPEC A.6) - a narrower
 * band would flicker on measurement noise instead of on the string.
 */
fun bandFor(cents: Double): TuningBand = when {
    abs(cents) <= 1.0 -> TuningBand.IN_TUNE
    abs(cents) <= 5.0 -> TuningBand.CLOSE
    else -> TuningBand.OUT
}

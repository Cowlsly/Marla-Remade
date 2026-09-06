package com.vayunmathur.tuner.domain

import com.vayunmathur.tuner.data.Instrument
import com.vayunmathur.tuner.data.MUTED_FRET

/**
 * One playable shape.
 *
 * [frets] is one entry per string in the instrument's own string order, [MUTED_FRET] for a
 * string that is not played and 0 for an open one. [soundingMidi] is what that shape actually
 * produces, which is what lets the same data drive the note readout under the diagram.
 */
data class Voicing(
    val frets: List<Int>,
    val barreFret: Int?,
    val fingerCount: Int,
    val soundingMidi: List<Int>,
    val cost: Double,
    /** The rank-1 shape: what the app recommends, marked in the pager. */
    val isPrimary: Boolean = false,
) {
    /** Lowest fretted position, or 0 when the shape is all open. Labels the pager page. */
    val position: Int get() = frets.filter { it > 0 }.minOrNull() ?: 0
}

/**
 * Generates chord shapes from the instrument's tuning rather than looking them up in a dataset
 * (TUNER_SPEC E.1).
 *
 * The deciding argument is that generation takes the tuning as *input*: drop-D, DADGAD, a
 * low-G ukulele or a seven-string all work with no extra data, whereas a shape database is keyed
 * to specific tunings and simply has no entry for them. The counter-argument - that a naive
 * generator proposes technically optimal shapes nobody plays - is real, and is why
 * [OpenShapeOverrides] exists.
 *
 * A shape is a **suggestion**, never a measurement. Which position was fingered is not
 * recoverable from audio, so the UI must never present a diagram as "what you played".
 */
object VoicingGenerator {
    private const val MAX_SPAN = 4
    private const val MAX_FINGERS = 4
    private const val MAX_INTERIOR_MUTES = 1
    private const val MAX_FRET = 12
    private const val WINDOW = 4

    /** Frets 0-4. A shape entirely inside this is what a chord book prints first. */
    private const val FIRST_POSITION = 4

    /** Neck-position buckets for the pager: open, mid, upper (TUNER_SPEC I.6). */
    private val BUCKETS = listOf(0..4, 5..8, 9..MAX_FRET)

    /**
     * Ranked shapes for [chord] on [instrument].
     *
     * The result is ordered by **ascending neck position**, not by cost: a player thinks
     * spatially, so swiping the pager should walk up the neck. The recommended shape is flagged
     * with [Voicing.isPrimary] rather than being placed first.
     *
     * [requiredBass] is a pitch class the lowest sounding string must produce - a hard
     * requirement when the detected chord carried a slash.
     */
    fun generate(
        instrument: Instrument,
        chord: ChordName,
        requiredBass: Int? = chord.bass,
    ): List<Voicing> {
        if (instrument.strings.isEmpty()) return emptyList()
        val tones = chord.pitchClasses
        val exact = enumerate(instrument, chord, tones, requiredBass)
        if (exact.isNotEmpty()) return finish(instrument, chord, exact)

        // Nothing fits with every tone present. Drop the 5th first, then the root - never the
        // 3rd, which carries major-versus-minor, and never the 7th, which is the reason the
        // chord is a 7th. A four-string instrument with a four-note chord drops nothing.
        if (instrument.strings.size <= chord.quality.intervals.size) return emptyList()
        for (droppable in listOf(7, 0)) {
            if (droppable !in chord.quality.intervals) continue
            val reduced = tones - pitchClassOf(chord.root + droppable)
            if (reduced.size < 2) continue
            if (droppable == 0 && requiredBass == null) continue
            val relaxed = enumerate(instrument, chord, reduced, requiredBass)
            if (relaxed.isNotEmpty()) return finish(instrument, chord, relaxed)
        }
        return emptyList()
    }

    private fun enumerate(
        instrument: Instrument,
        chord: ChordName,
        required: Set<Int>,
        requiredBass: Int?,
    ): List<List<Int>> {
        val playable = chord.pitchClasses
        val stringCount = instrument.strings.size
        val results = ArrayList<List<Int>>()
        val current = IntArray(stringCount)
        val topWindow = (instrument.fretCount - WINDOW + 1).coerceAtMost(MAX_FRET - WINDOW + 2)

        for (basePosition in 0..topWindow.coerceAtLeast(0)) {
            val candidates = Array(stringCount) { string ->
                val open = instrument.strings[string].openMidi
                val options = ArrayList<Int>(3 + playable.size)
                options += MUTED_FRET
                if (pitchClassOf(open) in playable) options += 0
                for (fret in basePosition..(basePosition + WINDOW - 1)) {
                    if (fret == 0 || fret > MAX_FRET || fret > instrument.fretCount) continue
                    if (pitchClassOf(open + fret) in playable) options += fret
                }
                options.toIntArray()
            }
            current.fill(MUTED_FRET)
            walk(instrument, candidates, current, 0, MAX_FRET + 1, 0, required, requiredBass, results)
        }
        return results
    }

    private fun walk(
        instrument: Instrument,
        candidates: Array<IntArray>,
        current: IntArray,
        string: Int,
        lowFret: Int,
        highFret: Int,
        required: Set<Int>,
        requiredBass: Int?,
        out: MutableList<List<Int>>,
    ) {
        if (string == current.size) {
            if (accept(instrument, current, required, requiredBass)) out += current.toList()
            return
        }
        for (fret in candidates[string]) {
            val low = if (fret > 0) minOf(lowFret, fret) else lowFret
            val high = if (fret > 0) maxOf(highFret, fret) else highFret
            // Prune on span as soon as it is exceeded; every deeper choice inherits the failure.
            if (high > 0 && low <= MAX_FRET && high - low >= MAX_SPAN) continue
            current[string] = fret
            walk(instrument, candidates, current, string + 1, low, high, required, requiredBass, out)
        }
        current[string] = MUTED_FRET
    }

    private fun accept(
        instrument: Instrument,
        frets: IntArray,
        required: Set<Int>,
        requiredBass: Int?,
    ): Boolean {
        val sounding = frets.indices.filter { frets[it] != MUTED_FRET }
        if (sounding.size < required.size) return false

        val produced = sounding.map { pitchClassOf(instrument.strings[it].openMidi + frets[it]) }
        if (!produced.containsAll(required)) return false

        val fretted = frets.filter { it > 0 }
        if (fretted.isNotEmpty() && fretted.max() - fretted.min() >= MAX_SPAN) return false

        var interior = 0
        val first = sounding.firstOrNull() ?: return false
        val last = sounding.last()
        for (i in first..last) if (frets[i] == MUTED_FRET) interior++
        if (interior > MAX_INTERIOR_MUTES) return false

        if (fingerCount(frets, barreFret(frets)) > MAX_FINGERS) return false

        // Pitch order, not string order: on a re-entrant tuning the bass is not string zero.
        val lowest = sounding.minBy { instrument.strings[it].openMidi + frets[it] }
        val lowestPitchClass = pitchClassOf(instrument.strings[lowest].openMidi + frets[lowest])
        val wanted = requiredBass ?: return true
        return lowestPitchClass == wanted
    }

    /**
     * The fret a barre sits at, or `null`.
     *
     * Valid when two or more strings are fretted at the lowest fretted position and no string
     * between the outermost of them and the top of the neck is required open - an open string
     * under the finger is not playable.
     */
    private fun barreFret(frets: IntArray): Int? {
        val lowest = frets.filter { it > 0 }.minOrNull() ?: return null
        val atLowest = frets.indices.filter { frets[it] == lowest }
        if (atLowest.size < 2) return null
        for (i in atLowest.first()..frets.lastIndex) {
            if (frets[i] == 0) return null
        }
        return lowest
    }

    private fun fingerCount(frets: IntArray, barre: Int?): Int =
        if (barre == null) frets.count { it > 0 } else 1 + frets.count { it > barre }

    /**
     * Picks the primary plus one shape per neck-position bucket.
     *
     * Ranking by cost alone returns five near-identical shapes a fret or two apart, which is
     * useless in a swipeable pager - the point of an alternate is that it is somewhere else on
     * the neck (TUNER_SPEC I.6).
     */
    private fun finish(
        instrument: Instrument,
        chord: ChordName,
        shapes: List<List<Int>>,
    ): List<Voicing> {
        val seen = HashSet<List<Int>>()
        val scored = ArrayList<Voicing>()
        for (shape in shapes) {
            if (!seen.add(shape)) continue
            scored += describe(instrument, chord, shape)
        }
        val override = OpenShapeOverrides.shapeFor(instrument.id, chord)
        val ranked = scored.sortedBy { it.cost }.toMutableList()
        if (override != null) {
            val preferred = describe(instrument, chord, override).copy(cost = Double.NEGATIVE_INFINITY)
            ranked.removeAll { it.frets == preferred.frets }
            ranked.add(0, preferred)
        }
        val primary = ranked.firstOrNull() ?: return emptyList()

        // Four strings and a short neck run out of genuinely distinct shapes sooner than six do.
        val limit = if (instrument.strings.size >= 5) 4 else 3
        val chosen = LinkedHashSet<List<Int>>()
        chosen += primary.frets
        for (bucket in BUCKETS) {
            if (chosen.size >= limit) break
            if (primary.position in bucket) continue
            val best = ranked.firstOrNull { it.position in bucket && it.frets !in chosen } ?: continue
            chosen += best.frets
        }
        for (candidate in ranked) {
            if (chosen.size >= limit) break
            chosen += candidate.frets
        }

        return ranked.asSequence()
            .filter { it.frets in chosen }
            .distinctBy { it.frets }
            .map { it.copy(isPrimary = it.frets == primary.frets) }
            .sortedBy { it.position }
            .toList()
    }

    private fun describe(instrument: Instrument, chord: ChordName, shape: List<Int>): Voicing {
        val frets = shape.toIntArray()
        val barre = barreFret(frets)
        val fingers = fingerCount(frets, barre)
        val sounding = shape.indices.filter { shape[it] != MUTED_FRET }
        val midi = sounding.map { instrument.strings[it].openMidi + shape[it] }
        val fretted = shape.filter { it > 0 }
        val span = if (fretted.isEmpty()) 0 else fretted.max() - fretted.min() + 1
        val minFret = fretted.minOrNull() ?: 0
        val firstPosition = fretted.all { it <= FIRST_POSITION }
        val openCount = shape.count { it == 0 }
        var interior = 0
        if (sounding.isNotEmpty()) {
            for (i in sounding.first()..sounding.last()) if (shape[i] == MUTED_FRET) interior++
        }
        val lowestIsRoot = midi.isNotEmpty() && pitchClassOf(midi.min()) == chord.root
        val fifth = pitchClassOf(chord.root + 7)
        val fifthDropped = 7 in chord.quality.intervals && midi.none { pitchClassOf(it) == fifth }

        // TUNER_SPEC I.6. The first-position term dominates deliberately: "what a chord book
        // shows first" is the ordering principle here, not a tiebreak between similar shapes.
        val cost = -2.5 * (if (firstPosition) 1 else 0) -
            0.7 * openCount -
            1.0 * (if (lowestIsRoot) 1 else 0) +
            1.2 * fingers +
            1.0 * span +
            0.8 * (if (barre != null) 1 else 0) +
            0.30 * minFret +
            3.0 * interior +
            1.5 * (if (fifthDropped) 1 else 0)

        return Voicing(shape, barre, fingers, midi.sorted(), cost)
    }
}

/**
 * The thirty-odd open-position shapes a player actually expects, which a cost function will not
 * reliably rediscover.
 *
 * This is our own content, so there is no licence question and no vendoring process - the two
 * things that made a third-party shape database expensive for what it buys. If this table ever
 * needs to grow past a couple of hundred entries, that is the honest trigger to revisit the
 * decision not to use one (TUNER_SPEC E.2).
 */
object OpenShapeOverrides {
    private val guitar: Map<Pair<Int, ChordQuality>, List<Int>> = mapOf(
        (4 to ChordQuality.MAJOR) to listOf(0, 2, 2, 1, 0, 0),
        (4 to ChordQuality.MINOR) to listOf(0, 2, 2, 0, 0, 0),
        (4 to ChordQuality.DOMINANT_SEVENTH) to listOf(0, 2, 0, 1, 0, 0),
        (4 to ChordQuality.MINOR_SEVENTH) to listOf(0, 2, 0, 0, 0, 0),
        (9 to ChordQuality.MAJOR) to listOf(-1, 0, 2, 2, 2, 0),
        (9 to ChordQuality.MINOR) to listOf(-1, 0, 2, 2, 1, 0),
        (9 to ChordQuality.DOMINANT_SEVENTH) to listOf(-1, 0, 2, 0, 2, 0),
        (9 to ChordQuality.MINOR_SEVENTH) to listOf(-1, 0, 2, 0, 1, 0),
        (9 to ChordQuality.MAJOR_SEVENTH) to listOf(-1, 0, 2, 1, 2, 0),
        (2 to ChordQuality.MAJOR) to listOf(-1, -1, 0, 2, 3, 2),
        (2 to ChordQuality.MINOR) to listOf(-1, -1, 0, 2, 3, 1),
        (2 to ChordQuality.DOMINANT_SEVENTH) to listOf(-1, -1, 0, 2, 1, 2),
        (2 to ChordQuality.MINOR_SEVENTH) to listOf(-1, -1, 0, 2, 1, 1),
        (2 to ChordQuality.MAJOR_SEVENTH) to listOf(-1, -1, 0, 2, 2, 2),
        (7 to ChordQuality.MAJOR) to listOf(3, 2, 0, 0, 0, 3),
        (7 to ChordQuality.DOMINANT_SEVENTH) to listOf(3, 2, 0, 0, 0, 1),
        (0 to ChordQuality.MAJOR) to listOf(-1, 3, 2, 0, 1, 0),
        (0 to ChordQuality.DOMINANT_SEVENTH) to listOf(-1, 3, 2, 3, 1, 0),
        (0 to ChordQuality.MAJOR_SEVENTH) to listOf(-1, 3, 2, 0, 0, 0),
        (5 to ChordQuality.MAJOR) to listOf(1, 3, 3, 2, 1, 1),
        (5 to ChordQuality.MAJOR_SEVENTH) to listOf(-1, -1, 3, 2, 1, 0),
        (11 to ChordQuality.DOMINANT_SEVENTH) to listOf(-1, 2, 1, 2, 0, 2),
        (11 to ChordQuality.MINOR) to listOf(-1, 2, 4, 4, 3, 2),
    )

    private val ukulele: Map<Pair<Int, ChordQuality>, List<Int>> = mapOf(
        (0 to ChordQuality.MAJOR) to listOf(0, 0, 0, 3),
        (0 to ChordQuality.DOMINANT_SEVENTH) to listOf(0, 0, 0, 1),
        (0 to ChordQuality.MAJOR_SEVENTH) to listOf(0, 0, 0, 2),
        (9 to ChordQuality.MINOR) to listOf(2, 0, 0, 0),
        (9 to ChordQuality.MAJOR) to listOf(2, 1, 0, 0),
        (9 to ChordQuality.DOMINANT_SEVENTH) to listOf(0, 1, 0, 0),
        (5 to ChordQuality.MAJOR) to listOf(2, 0, 1, 0),
        (7 to ChordQuality.MAJOR) to listOf(0, 2, 3, 2),
        (7 to ChordQuality.DOMINANT_SEVENTH) to listOf(0, 2, 1, 2),
        (2 to ChordQuality.MAJOR) to listOf(2, 2, 2, 0),
        (2 to ChordQuality.MINOR) to listOf(2, 2, 1, 0),
        (2 to ChordQuality.DOMINANT_SEVENTH) to listOf(2, 2, 2, 3),
        (4 to ChordQuality.MINOR) to listOf(0, 4, 3, 2),
        (10 to ChordQuality.MAJOR) to listOf(3, 2, 1, 1),
    )

    /** The expected shape for [chord] on [instrumentId], or `null` when there is no override. */
    fun shapeFor(instrumentId: String, chord: ChordName): List<Int>? {
        if (chord.bass != null) return null
        val table = when (instrumentId) {
            "guitar-standard" -> guitar
            "ukulele-gcea" -> ukulele
            else -> return null
        }
        return table[chord.root to chord.quality]
    }
}

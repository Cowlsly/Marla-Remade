package com.vayunmathur.measure.domain

import com.vayunmathur.measure.data.model.UnitSystem
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Length, area and angle formatting shared by every tool.
 *
 * All values enter in SI (metres, square metres) and are converted at the display edge, so
 * no tool has to care which unit system is active.
 */
object Units {

    const val MM_PER_INCH = 25.4
    const val MM_PER_FOOT = 304.8
    private const val M2_PER_FT2 = 0.09290304

    /** Denominator for fractional-inch display. Sixteenths is the tape-measure convention. */
    private const val FRACTION_DENOMINATOR = 16

    fun formatLength(
        metres: Double,
        system: UnitSystem,
        fractionalInches: Boolean = true,
    ): String = when (system) {
        UnitSystem.Metric -> formatMetric(metres)
        UnitSystem.Imperial -> formatImperial(metres, fractionalInches)
    }

    private fun formatMetric(metres: Double): String {
        val mm = metres * 1000.0
        return when {
            abs(mm) < 10.0 -> "%.1f mm".format(mm)
            abs(mm) < 1000.0 -> "%.0f mm".format(mm)
            abs(metres) < 10.0 -> "%.3f m".format(metres)
            else -> "%.2f m".format(metres)
        }
    }

    private fun formatImperial(metres: Double, fractional: Boolean): String {
        val totalInches = metres * 1000.0 / MM_PER_INCH
        if (!fractional) {
            return if (abs(totalInches) < 12.0) {
                "%.2f in".format(totalInches)
            } else {
                "%.2f ft".format(totalInches / 12.0)
            }
        }
        return formatFractionalInches(totalInches)
    }

    /**
     * Renders inches the way a tape measure reads: whole feet, whole inches, and a reduced
     * sixteenth. Reduction matters — `3/16` is correct but `8/16` should read `1/2`.
     */
    fun formatFractionalInches(totalInches: Double): String {
        val negative = totalInches < 0
        val abs = abs(totalInches)

        val sixteenths = (abs * FRACTION_DENOMINATOR).roundToLong()
        var wholeInches = sixteenths / FRACTION_DENOMINATOR
        val remainder = (sixteenths % FRACTION_DENOMINATOR).toInt()

        val feet = wholeInches / 12
        wholeInches %= 12

        val sb = StringBuilder()
        if (negative) sb.append('-')
        if (feet > 0) {
            sb.append(feet).append("' ")
        }
        sb.append(wholeInches)
        if (remainder > 0) {
            var num = remainder
            var den = FRACTION_DENOMINATOR
            while (num % 2 == 0 && den % 2 == 0) {
                num /= 2
                den /= 2
            }
            sb.append(' ').append(num).append('/').append(den)
        }
        sb.append('"')
        return sb.toString()
    }

    fun formatArea(squareMetres: Double, system: UnitSystem): String = when (system) {
        UnitSystem.Metric -> if (squareMetres < 1.0) {
            "%.0f cm²".format(squareMetres * 10_000.0)
        } else {
            "%.2f m²".format(squareMetres)
        }
        UnitSystem.Imperial -> {
            val ft2 = squareMetres / M2_PER_FT2
            if (ft2 < 1.0) "%.0f in²".format(ft2 * 144.0) else "%.2f ft²".format(ft2)
        }
    }

    fun formatAngle(degrees: Double): String = "%.1f°".format(degrees)

    /** Compass bearings read better as whole degrees; sub-degree precision is noise anyway. */
    fun formatBearing(degrees: Double): String = "%03d°".format(normalizeDegrees(degrees).roundToInt() % 360)

    fun normalizeDegrees(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    /**
     * Shortest signed delta between two bearings, in `[-180, 180)`.
     *
     * An exact half-turn is ambiguous — +180 and −180 describe the same rotation — and
     * this convention resolves it to −180.
     */
    fun bearingDelta(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0

    /** Number of named compass points on the sixteen-point rose. */
    const val CARDINAL_POINTS = 16

    /**
     * Index into the sixteen-point compass rose, clockwise from north.
     *
     * Returns an index rather than a label because the labels are localised and live in
     * `R.array.compass_cardinals`; this package holds no Android dependencies.
     */
    fun cardinalIndex(degrees: Double): Int =
        ((normalizeDegrees(degrees) / (360.0 / CARDINAL_POINTS)) + 0.5).toInt() % CARDINAL_POINTS
}

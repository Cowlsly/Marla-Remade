package com.vayunmathur.games.wordmaker.data

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How far apart the letters sit on the wheel.
 *
 * Letter tiles are a fixed 70.dp, so at [DEFAULT] an 8-letter puzzle leaves only about 65.dp
 * between neighbouring centres — the tiles touch and the trace lines between them are hard to
 * see and to aim at (#569). Widening the ring is what buys the gap back, so the setting scales
 * the ring radius (and the box that has to hold it) rather than shrinking the letters.
 */
enum class WheelSpacing(val ringRadius: Dp) {
    DEFAULT(85.dp),
    WIDE(100.dp),
    WIDER(115.dp),
    WIDEST(130.dp);

    /** Side of the square the wheel occupies: the ring plus a tile's worth of margin. */
    val boxSize: Dp get() = ringRadius * 2 + 80.dp
}

package com.vayunmathur.library.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Named haptics, so a call site says what it is confirming rather than which waveform it wants.
 *
 * A thin wrapper over [LocalHapticFeedback] for the same reason the Material aliases exist: the
 * raw [HapticFeedbackType] constants are a long, mostly-irrelevant list, and picking from it at
 * each call site is how one screen ends up buzzing differently from the next for the same event.
 *
 * Deliberately small. Haptics that fire often - a tick per grid cell crossed during a drag - have
 * to be genuinely light, and there is no reason for an app to reach past these four.
 */
@Immutable
class Haptics internal constructor(private val feedback: HapticFeedback) {

    /** Something has been picked up: a long press has taken the gesture. */
    fun longPress() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)

    /**
     * A boundary was crossed - a new drop target, a slider notch. The lightest thing available,
     * because this fires repeatedly while the finger is moving.
     */
    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)

    /** An action completed: a drop landed, a toggle took. */
    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)

    /** An action was refused: a drop with nowhere to go. */
    fun reject() = feedback.performHapticFeedback(HapticFeedbackType.Reject)
}

/** The [Haptics] for this composition. */
@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}

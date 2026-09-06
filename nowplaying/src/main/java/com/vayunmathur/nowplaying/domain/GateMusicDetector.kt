package com.vayunmathur.nowplaying.domain

import com.vayunmathur.library.ml.MusicGate

/**
 * The real detector: the always-on music gate, running on the CPU in `:library:ml`.
 *
 * Adapts the gate's streaming interface to this app's window-oriented one. [MusicGate.push] is fed
 * a whole hop at a time, so it completes exactly one hop per call and returns at most one
 * probability; during the gate's ~23-hop warm-up it returns none, which becomes the null that
 * [MusicDetector.score] already defines as "not yet meaningful".
 *
 * Not thread-safe, because the gate is not: one [score] at a time and no [score] concurrent with
 * [close].
 */
class GateMusicDetector private constructor(private val gate: MusicGate) : MusicDetector {

    override val isAvailable: Boolean get() = gate.isAvailable

    override val windowSamples = MusicGate.HOP_SAMPLES

    override fun score(window: ShortArray): Float? = gate.push(window)?.lastOrNull()

    override fun reset() = gate.reset()

    override fun close() = gate.close()

    companion object {
        /** Build a detector. Never throws; check [isAvailable]. */
        fun inProcess(): GateMusicDetector = GateMusicDetector(MusicGate.inProcess())
    }
}

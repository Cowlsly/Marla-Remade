package com.vayunmathur.sdk.cast

import android.os.Bundle

/**
 * Where playback is, for the television to draw a seek bar from.
 *
 * **A deliberate second definition of `:cast:protocol`'s message of the same name, not a reuse of
 * it.** This module is the public client contract: every app that casts compiles against it, and
 * giving it a dependency on `:cast:protocol` would hand each of them the wire format, the crypto and
 * the RTP packetiser - which is the entire thing brokering exists to avoid. `:cast` depends on both
 * sides and owns the translation, so the duplication is one file wide and two functions deep, and it
 * buys a public API that cannot leak the protocol.
 *
 * A snapshot rather than a delta: absolute in every field, so a lost one repairs itself on the next
 * heartbeat and there is no sequence number to get wrong.
 */
data class PlaybackState(
    val positionMs: Long,
    /** Zero or less for something with no known end, which the TV draws without a bar. */
    val durationMs: Long,
    /**
     * Whether media is actually advancing.
     *
     * Report the player's own "is playing" rather than its play-when-ready flag: the TV extrapolates
     * position between snapshots, and a stall reported as playing runs its seek bar ahead of the
     * picture and then snaps it back.
     */
    val playing: Boolean,
    /** For a spinner. Distinct from [playing], because a stall is not a pause. */
    val buffering: Boolean = false,
    /** Tempo multiplier, 1.0 being normal. */
    val speed: Float = 1f,
    /** Media volume as 0..1, the same level on both ends. */
    val volume: Float = 1f,
    /**
     * Whether there is anything to skip to.
     *
     * Carried because the TV cannot know - what comes next is this app's own idea of a queue - and a
     * remote offering a button that does nothing is worse than one offering none.
     */
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
) {
    internal fun toBundle(): Bundle = Bundle().apply {
        putLong(CastContract.KEY_POSITION_MS, positionMs)
        putLong(CastContract.KEY_DURATION_MS, durationMs)
        putBoolean(CastContract.KEY_PLAYING, playing)
        putBoolean(CastContract.KEY_BUFFERING, buffering)
        putFloat(CastContract.KEY_SPEED, speed)
        putFloat(CastContract.KEY_VOLUME, volume)
        putBoolean(CastContract.KEY_HAS_NEXT, hasNext)
        putBoolean(CastContract.KEY_HAS_PREVIOUS, hasPrevious)
    }
}

/**
 * A press on the television's remote.
 *
 * [value] is the action's argument for the actions that take one - milliseconds for
 * [PlaybackAction.SeekTo], a multiplier for [PlaybackAction.SetSpeed], 0..1 for
 * [PlaybackAction.SetVolume] - and null for the rest. One nullable field rather than three
 * mostly-absent ones, which cannot then disagree.
 */
data class PlaybackCommand(
    val action: PlaybackAction,
    val value: Double? = null,
) {
    companion object {
        /**
         * Read one back, or null for an action this build does not know.
         *
         * Null rather than an exception on purpose: a newer Cast gaining a button must not crash an
         * app built against an older SDK, and ignoring the press is the correct behaviour anyway.
         */
        internal fun from(data: Bundle?): PlaybackCommand? {
            if (data == null) return null
            val action = PlaybackAction.of(data.getInt(CastContract.KEY_ACTION, -1)) ?: return null
            val value = if (data.containsKey(CastContract.KEY_ACTION_VALUE)) {
                data.getDouble(CastContract.KEY_ACTION_VALUE)
            } else {
                null
            }
            return PlaybackCommand(action, value)
        }
    }
}

/**
 * What the remote asked for.
 *
 * The [wire] value is what crosses the `Bundle`; the Kotlin names are free to change. An enum is not
 * put in the `Bundle` directly because a `Serializable` enum there is a class-loading problem waiting
 * for a client compiled against a different SDK version.
 */
enum class PlaybackAction(internal val wire: Int) {
    Play(CastContract.ACTION_PLAY),
    Pause(CastContract.ACTION_PAUSE),

    /**
     * Whichever of the two the player is not currently doing.
     *
     * Its own action rather than the TV resolving it from the last snapshot: that snapshot can be half
     * a second old, and two quick presses resolved against it would both send the same thing.
     */
    Toggle(CastContract.ACTION_TOGGLE),

    /** Absolute position, in milliseconds. */
    SeekTo(CastContract.ACTION_SEEK_TO),

    /** The app's own skip interval, so the two ends cannot disagree about how far it is. */
    SkipForward(CastContract.ACTION_SKIP_FORWARD),
    SkipBack(CastContract.ACTION_SKIP_BACK),

    Next(CastContract.ACTION_NEXT),
    Previous(CastContract.ACTION_PREVIOUS),

    /** A tempo multiplier. */
    SetSpeed(CastContract.ACTION_SET_SPEED),

    /**
     * A 0..1 level for *both* ends.
     *
     * The app is expected to move its own media volume to match, so the level survives the session
     * ending and local playback resumes where the television left it.
     */
    SetVolume(CastContract.ACTION_SET_VOLUME),
    ;

    internal companion object {
        fun of(wire: Int): PlaybackAction? = entries.firstOrNull { it.wire == wire }
    }
}

package com.vayunmathur.cast.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two messages that carry a transport in either direction, and the one piece of arithmetic behind
 * a seek bar.
 *
 * The interpolation is here rather than in `:cast:tv` deliberately: it is a pure function of a
 * snapshot and an elapsed time, so it is provable on the JVM, and a seek bar that drifts or overshoots
 * is otherwise only visible by watching a television.
 */
class PlaybackTest {

    private val codec = ControlCodec()

    @Test
    fun `both playback messages round-trip through the codec`() {
        val messages = listOf<ControlMessage>(
            PlaybackState(
                positionMs = 42_500,
                durationMs = 600_000,
                playing = true,
                buffering = false,
                speed = 1.5f,
                volume = 0.4f,
                hasNext = true,
                hasPrevious = false,
            ),
            // Every default at once, which is the case `encodeDefaults` could quietly eat.
            PlaybackState(positionMs = 0, durationMs = 0, playing = false, buffering = true),
            // A track that finished on its own, which is what makes the other end advance its queue.
            PlaybackState(positionMs = 214_000, durationMs = 214_000, playing = false, buffering = false, ended = true),
            PlaybackCommand(PlaybackAction.Toggle),
            PlaybackCommand(PlaybackAction.SeekTo, value = 125_000.0),
            PlaybackCommand(PlaybackAction.SetSpeed, value = 2.0),
            PlaybackCommand(PlaybackAction.SetVolume, value = 0.0),
        )
        for (message in messages) {
            assertEquals(message, codec.decode(codec.encode(message)), "$message did not round-trip")
        }
    }

    @Test
    fun `an item still playing is not reported as ended`() {
        // Defaulted, so a sender written against the older contract still means what it meant - and a
        // spurious `ended` would skip the track it was describing.
        val playing = PlaybackState(1_000, 214_000, playing = true, buffering = false)
        val back = codec.decode(codec.encode(playing)) as PlaybackState
        assertTrue(!back.ended)
    }

    @Test
    fun `action names on the wire are the stable ones, not the Kotlin identifiers`() {
        // Renaming `Toggle` or `SkipBack` must not change the wire format: the failure would be a
        // transport whose buttons stop working while both ends agree they are on version 6.
        for ((action, wire) in mapOf(
            PlaybackAction.Play to "PLAY",
            PlaybackAction.Pause to "PAUSE",
            PlaybackAction.Toggle to "TOGGLE",
            PlaybackAction.SeekTo to "SEEK_TO",
            PlaybackAction.SkipForward to "SKIP_FORWARD",
            PlaybackAction.SkipBack to "SKIP_BACK",
            PlaybackAction.Next to "NEXT",
            PlaybackAction.Previous to "PREVIOUS",
            PlaybackAction.SetSpeed to "SET_SPEED",
            PlaybackAction.SetVolume to "SET_VOLUME",
        )) {
            val body = codec.encode(PlaybackCommand(action)).toString(Charsets.UTF_8)
            assertTrue(body.contains("\"action\":\"$wire\""), body)
        }
        assertTrue(
            codec.encode(PlaybackCommand(PlaybackAction.Play))
                .toString(Charsets.UTF_8)
                .contains("\"type\":\"PLAYBACK_COMMAND\""),
        )
        assertTrue(
            codec.encode(PlaybackState(0, 0, playing = false, buffering = false))
                .toString(Charsets.UTF_8)
                .contains("\"type\":\"PLAYBACK_STATE\""),
        )
    }

    @Test
    fun `an argumentless command carries no value rather than a null one`() {
        val body = codec.encode(PlaybackCommand(PlaybackAction.Next)).toString(Charsets.UTF_8)
        assertTrue(!body.contains("value"), body)
    }

    @Test
    fun `a paused snapshot holds still however long it is held`() {
        val paused = PlaybackState(30_000, 600_000, playing = false, buffering = false)
        assertEquals(30_000, paused.interpolated(0))
        assertEquals(30_000, paused.interpolated(5_000))
        assertEquals(30_000, paused.interpolated(600_000))
    }

    @Test
    fun `a playing snapshot advances with elapsed wall-clock time`() {
        val playing = PlaybackState(30_000, 600_000, playing = true, buffering = false)
        assertEquals(30_000, playing.interpolated(0))
        assertEquals(30_500, playing.interpolated(500))
        assertEquals(31_000, playing.interpolated(1_000))
    }

    @Test
    fun `speed scales the advance, so a 2x video does not lag its own bar`() {
        val fast = PlaybackState(0, 600_000, playing = true, buffering = false, speed = 2f)
        assertEquals(1_000, fast.interpolated(500))
        val slow = PlaybackState(0, 600_000, playing = true, buffering = false, speed = 0.5f)
        assertEquals(250, slow.interpolated(500))
    }

    @Test
    fun `a fresh snapshot re-anchors rather than compounding the last estimate`() {
        // What makes drift bounded by one heartbeat: the phone's number always wins, even when it
        // moves backwards because the user scrubbed on the phone.
        val first = PlaybackState(10_000, 600_000, playing = true, buffering = false)
        assertEquals(10_500, first.interpolated(500))
        val second = PlaybackState(3_000, 600_000, playing = true, buffering = false)
        assertEquals(3_500, second.interpolated(500))
    }

    @Test
    fun `interpolation stops at the end rather than running past it`() {
        val nearEnd = PlaybackState(599_800, 600_000, playing = true, buffering = false)
        assertEquals(600_000, nearEnd.interpolated(5_000))
    }

    @Test
    fun `an unknown duration still advances, because a live stream has no end to stop at`() {
        val live = PlaybackState(1_000, 0, playing = true, buffering = false)
        assertEquals(6_000, live.interpolated(5_000))
    }

    @Test
    fun `nonsense inputs produce a position rather than a negative one`() {
        // A clock that went backwards, and a phone that reported a position before the start. Both
        // are drawable as zero; neither should put a bar off the left of the screen.
        val playing = PlaybackState(1_000, 600_000, playing = true, buffering = false)
        assertEquals(1_000, playing.interpolated(-500))
        assertEquals(0, PlaybackState(-5, 600_000, playing = false, buffering = false).interpolated(0))
    }
}

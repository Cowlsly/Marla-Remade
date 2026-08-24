package com.vayunmathur.music.service

import androidx.media3.common.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The arithmetic and the derivations behind the one player the phone presents while casting.
 *
 * These are the parts of [CastingPlayer] that can be pinned down without a television and without a
 * looper. Everything else in that class is media3 asking it questions on the application thread, which
 * needs a device - so what is testable is deliberately factored out rather than left inline: a
 * `playWhenReady` derived wrongly takes the notification away mid-track, and a position anchored
 * wrongly makes every seek bar in the app jerk twice a second, and neither is obvious from reading it.
 *
 * `availableCommands` is exercised through [CastingPlayer.seekable], which is the whole of the
 * decision. The `Player.Commands` object it feeds is not built here: media3's flag sets are backed by
 * `SparseBooleanArray`, so constructing one on a plain JVM gets a stubbed Android class rather than a
 * failure, which would make the assertion say nothing.
 */
class CastingPlayerTest {

    // ---- what the phone reports ----

    @Test
    fun `a playing television is a player that is playing`() {
        val playWhenReady = CastingPlayer.playWhenReady(playing = true, buffering = false)
        val state = CastingPlayer.playbackStateFor(buffering = false, ended = false)
        assertTrue(playWhenReady)
        assertEquals(Player.STATE_READY, state)
        assertTrue(isPlaying(playWhenReady, state))
    }

    @Test
    fun `a paused television is a player that wants to be paused`() {
        // `playWhenReady` false is the load-bearing half: media3's foreground-service lifecycle reads
        // it, so a pause reported as anything else leaves a notification for a player making no sound.
        val playWhenReady = CastingPlayer.playWhenReady(playing = false, buffering = false)
        val state = CastingPlayer.playbackStateFor(buffering = false, ended = false)
        assertFalse(playWhenReady)
        assertEquals(Player.STATE_READY, state)
        assertFalse(isPlaying(playWhenReady, state))
    }

    @Test
    fun `a stall still wants to play, and still is not playing`() {
        // The television reports its player's own `isPlaying`, which is already false while it stalls.
        // Read as a pause that would grey out the notification's pause button mid-track; what makes it
        // come out right is the state, not the flag.
        val playWhenReady = CastingPlayer.playWhenReady(playing = false, buffering = true)
        val state = CastingPlayer.playbackStateFor(buffering = true, ended = false)
        assertTrue(playWhenReady, "a buffering television is still trying to play")
        assertEquals(Player.STATE_BUFFERING, state)
        assertFalse(isPlaying(playWhenReady, state), "a stall is not playback")
    }

    @Test
    fun `a finished track ends rather than buffers`() {
        // `ended` is what makes the queue advance, so it must win: a track that finished while its
        // last read was outstanding reports both, and STATE_BUFFERING would wait for ever.
        assertEquals(
            Player.STATE_ENDED,
            CastingPlayer.playbackStateFor(buffering = true, ended = true),
        )
    }

    /** media3's own derivation, spelled out here rather than duplicated into the player. */
    private fun isPlaying(playWhenReady: Boolean, playbackState: Int): Boolean =
        playWhenReady && playbackState == Player.STATE_READY

    // ---- where the seek bar is ----

    @Test
    fun `a paused snapshot holds still however long it is held`() {
        for (elapsed in listOf(0L, 500L, 600_000L)) {
            assertEquals(
                30_000,
                CastingPlayer.anchoredPositionMs(30_000, speed = 1f, playing = false, elapsedMs = elapsed),
            )
        }
    }

    @Test
    fun `a playing snapshot advances with the wall clock`() {
        assertEquals(30_000, CastingPlayer.anchoredPositionMs(30_000, 1f, playing = true, elapsedMs = 0))
        assertEquals(30_500, CastingPlayer.anchoredPositionMs(30_000, 1f, playing = true, elapsedMs = 500))
    }

    @Test
    fun `speed scales the advance, so a 2x track does not lag its own bar`() {
        assertEquals(1_000, CastingPlayer.anchoredPositionMs(0, 2f, playing = true, elapsedMs = 500))
        assertEquals(250, CastingPlayer.anchoredPositionMs(0, 0.5f, playing = true, elapsedMs = 500))
    }

    @Test
    fun `a clock that went backwards answers where playback was`() {
        assertEquals(1_000, CastingPlayer.anchoredPositionMs(1_000, 1f, playing = true, elapsedMs = -500))
    }

    // ---- what counts as a jump ----

    @Test
    fun `ordinary drift between snapshots is not a discontinuity`() {
        // Snapshots land twice a second against an estimate that keeps running, so the two are never
        // exactly equal. Announcing that as a seek would make every heartbeat a discontinuity.
        assertFalse(CastingPlayer.isDiscontinuity(reportedMs = 30_000, extrapolatedMs = 30_000))
        assertFalse(CastingPlayer.isDiscontinuity(reportedMs = 30_120, extrapolatedMs = 30_000))
        assertFalse(CastingPlayer.isDiscontinuity(reportedMs = 29_880, extrapolatedMs = 30_000))
        // The boundary itself is drift, not a jump.
        assertFalse(
            CastingPlayer.isDiscontinuity(30_000 + CastingPlayer.DISCONTINUITY_MS, 30_000),
        )
    }

    @Test
    fun `a scrub on the television's remote is a discontinuity in both directions`() {
        // The smallest thing the remote can do is five seconds, so the window has room to spare.
        assertTrue(CastingPlayer.isDiscontinuity(reportedMs = 35_000, extrapolatedMs = 30_000))
        assertTrue(CastingPlayer.isDiscontinuity(reportedMs = 25_000, extrapolatedMs = 30_000))
    }

    @Test
    fun `the window is narrower than the smallest scrub the remote can make`() {
        assertTrue(
            CastingPlayer.DISCONTINUITY_MS < 5_000L,
            "a scrub the remote can make must not be mistaken for drift",
        )
    }

    // ---- whether the track can be seeked ----

    @Test
    fun `a track with nothing being encoded can be seeked`() {
        assertTrue(CastingPlayer.seekable(growingResourceId = null, currentResourceId = "12"))
    }

    @Test
    fun `the track being encoded cannot be seeked`() {
        // A stream with no stated length has nothing to answer a byte range against, so the scrubber
        // is taken away rather than left to fail.
        assertFalse(CastingPlayer.seekable(growingResourceId = "12", currentResourceId = "12"))
    }

    @Test
    fun `another track being encoded does not take this one's scrubber away`() {
        // The case the gate exists for: a background transcode keeps running after the user has
        // skipped past it, and greying out the track they are actually listening to would be wrong.
        assertTrue(CastingPlayer.seekable(growingResourceId = "12", currentResourceId = "13"))
    }

    @Test
    fun `the scrubber comes back when the encode finishes`() {
        assertFalse(CastingPlayer.seekable("12", "12"))
        assertTrue(CastingPlayer.seekable(null, "12"), "no track change should be needed")
    }
}

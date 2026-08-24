package com.vayunmathur.music.service

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer.PositionSupplier
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.vayunmathur.music.platform.CastPlayback
import com.vayunmathur.sdk.cast.PlaybackAction
import com.vayunmathur.sdk.cast.PlaybackCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The phone's one player, whichever end is actually making the sound.
 *
 * **Wrapping the session's player is what fixes every surface at once.** `:music` has three
 * independent paths to its transport - the app's own UI through `PlaybackManager`, the mini-player's
 * media3 controller, and the notification, lockscreen, headset and Android Auto through
 * `MediaLibrarySession` - and they converge in exactly one place: the [Player] handed to
 * `MediaLibrarySession.Builder`. Intercepting anywhere higher fixes the first path and leaves the
 * notification claiming "paused" while the television plays, with its play button resuming the phone
 * so the track comes out of both.
 *
 * **[ForwardingSimpleBasePlayer] rather than `ForwardingPlayer`, and that is the load-bearing
 * choice.** The wrong one compiles and then fails silently: `ForwardingPlayer` delegates
 * `addListener` to the player it wraps, so it owns no listener registry and can never tell media3
 * that a getter changed value. `MediaSession` snapshots its `PlayerInfo` when the *wrapped* player
 * fires an event, and a paused local `ExoPlayer` fires almost nothing - so every controller would sit
 * on a frozen `playing = false` however good the getters were. This class gives [getState] for reads,
 * `invalidateState` for announcing them, and `handle*` for routing writes.
 *
 * Reads are synthesised and writes are routed, and only while casting. Everything that is the
 * *phone's* - the timeline, the metadata, the artwork, shuffle, repeat, the queue - is left entirely
 * to the delegate, which is what keeps the notification's title and cover working.
 */
@OptIn(UnstableApi::class)
class CastingPlayer(private val local: Player) : ForwardingSimpleBasePlayer(local) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** True between a cast starting and ending; nothing below applies otherwise. */
    private var casting = false

    /** The television's last word about its own player, or null before it has said anything. */
    private var tv: CastPlayback.TvPlayback? = null

    /** The resource still being encoded, which is the one that cannot be seeked. */
    private var growing: String? = null

    /**
     * Where playback is, extrapolating between snapshots.
     *
     * Rebuilt per snapshot so the bar moves smoothly rather than stepping twice a second - the same
     * idea as the protocol's own interpolation, one layer down, and media3 already has the primitive.
     */
    private var positions: PositionSupplier = PositionSupplier.ZERO

    /** A jump to announce once, so a seek on the TV's remote does not read as normal progress. */
    private var discontinuityMs: Long? = null

    init {
        // Plain `Main` rather than `Main.immediate`: a `StateFlow` hands over its current value on
        // subscription, and running that inline would call `invalidateState` from inside this
        // constructor. One posted tick later costs nothing.
        scope.launch {
            combine(
                CastPlayback.state,
                CastPlayback.tv,
                CastPlayback.growing,
            ) { state, snapshot, growingId -> Triple(state, snapshot, growingId) }
                .collect { (state, snapshot, growingId) -> onCast(state, snapshot, growingId) }
        }
    }

    private fun onCast(
        state: CastPlayback.State,
        snapshot: CastPlayback.TvPlayback?,
        growingId: String?,
    ) {
        casting = state is CastPlayback.State.Casting
        growing = growingId
        val current = snapshot.takeIf { casting }
        if (current == null) {
            tv = null
            positions = PositionSupplier.ZERO
            discontinuityMs = null
            invalidateState()
            return
        }
        // Compared against where the *old* supplier says we should be, before it is replaced. A press
        // on the television's remote is the case this exists for: without it a five-second jump is
        // indistinguishable from five seconds of ordinary progress, and nothing seeks.
        if (tv != null && isDiscontinuity(current.state.positionMs, positions.get())) {
            discontinuityMs = current.state.positionMs
        }
        tv = current
        positions = supplierFor(current)
        invalidateState()
    }

    /**
     * Everything the delegate says, with the television's player written over the parts it owns.
     *
     * The overridden set is deliberately small. Duration comes from the delegate's `MediaItem` and
     * never from the television, because a growing Opus stream has no duration and taking it from
     * there collapses every seek bar to zero on a first play. The timeline, the media ids, the
     * metadata and the artwork are the delegate's for the reason the class note gives - and because
     * unstable ids here would make every snapshot read as a timeline change, storming
     * `onTimelineChanged` and making Android Auto re-query its browse tree twice a second.
     */
    override fun getState(): State {
        val base = super.getState()
        val snapshot = tv ?: return base
        // Nothing loaded is nothing to describe, and `State` forbids a non-idle player with an empty
        // playlist anyway.
        if (base.timeline.isEmpty) return base
        val playback = snapshot.state
        val playbackState = playbackStateFor(playback.buffering, playback.ended)
        val builder = base.buildUpon()
            // `isPlaying` is media3's own derivation from these two, which is what keeps every surface
            // in step - and the foreground-service lifecycle, which reads `playWhenReady` and takes
            // the notification away mid-track if it is wrong.
            .setPlayWhenReady(
                playWhenReady(playback.playing, playback.buffering),
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE,
            )
            .setPlaybackState(playbackState)
            // The local player is paused and may be suppressed or hold an old error; neither says
            // anything about the television, and `State` forbids an error outside `STATE_IDLE`.
            .setPlaybackSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
            .setPlayerError(null)
            // Read off the state rather than off `buffering`, because `State` forbids loading in
            // `STATE_ENDED` - and a track that finished while its last read was outstanding reports
            // both, so this would throw on the application thread rather than merely look wrong.
            .setIsLoading(playbackState == Player.STATE_BUFFERING)
            .setPlaybackParameters(PlaybackParameters(playback.speed))
            .setContentPositionMs(positions)
            // The television reports no buffered extent, so the honest answer is "as far as it has
            // played" - and a buffered position behind the current one is rejected outright.
            .setContentBufferedPositionMs(positions)
            .setTotalBufferedDurationMs(PositionSupplier.ZERO)
            .setAvailableCommands(
                seekCommands(
                    base.availableCommands,
                    seekable(growing, local.currentMediaItem?.mediaId),
                ),
            )
        discontinuityMs?.let {
            discontinuityMs = null
            builder.setPositionDiscontinuity(Player.DISCONTINUITY_REASON_SEEK, it)
        }
        return builder.build()
    }

    /**
     * Play and pause become a command to the television, because that is where the player is.
     *
     * Not forwarded as well: the local player is what must stay silent, and starting it would put the
     * track through both the phone and the television at once.
     *
     * The future completes at once rather than when the television confirms, so media3 drops its
     * optimistic placeholder immediately and the next snapshot is what makes the change stick. That is
     * fine because the television reports on change rather than only on its heartbeat, so the
     * confirmation is one LAN round trip away - but it is the one thing here that can only be judged
     * with a television in the room: a press that visibly bounces back would mean waiting for the
     * confirming snapshot instead.
     */
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (!casting) return super.handleSetPlayWhenReady(playWhenReady)
        CastPlayback.send(
            PlaybackCommand(if (playWhenReady) PlaybackAction.Play else PlaybackAction.Pause),
        )
        return Futures.immediateVoidFuture()
    }

    /**
     * A seek inside the current track goes to the television; anything that changes item does not.
     *
     * Next and previous are **always** forwarded, because the queue is the phone's: the local player
     * moves, and the item transition is what issues a fresh `PLAY_MEDIA`. Routing them would ask a
     * television that has never seen the queue to guess at it.
     */
    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        if (!casting || !isWithinCurrentItem(seekCommand)) {
            return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
        CastPlayback.send(PlaybackCommand(PlaybackAction.SeekTo, positionMs.toDouble()))
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(
        playbackParameters: PlaybackParameters,
    ): ListenableFuture<*> {
        if (!casting) return super.handleSetPlaybackParameters(playbackParameters)
        CastPlayback.send(
            PlaybackCommand(PlaybackAction.SetSpeed, playbackParameters.speed.toDouble()),
        )
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return super.handleRelease()
    }

    /**
     * Where playback will be as the clock runs on, anchored at the moment the snapshot was true.
     *
     * Anchored rather than taken as "now" because the number crossed a LAN and an IPC hop to get here.
     * A paused player is a constant rather than an extrapolation at speed zero, because the seek bar
     * must hold still even if the television reports a speed it is not currently applying.
     */
    private fun supplierFor(playback: CastPlayback.TvPlayback): PositionSupplier {
        val state = playback.state
        val elapsed = System.currentTimeMillis() - playback.receivedAtMs
        val from = anchoredPositionMs(state.positionMs, state.speed, state.playing, elapsed)
        if (!state.playing) return PositionSupplier.getConstant(from)
        return PositionSupplier.getExtrapolating(from, state.speed)
    }

    private fun isWithinCurrentItem(seekCommand: Int): Boolean =
        seekCommand == Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM ||
            seekCommand == Player.COMMAND_SEEK_BACK ||
            seekCommand == Player.COMMAND_SEEK_FORWARD

    internal companion object {

        /**
         * Whether the user is asking for playback, from the two flags a snapshot carries.
         *
         * The television reports its player's own `isPlaying`, which is already false while it stalls -
         * so buffering has to count as wanting to play, or a stall would read as a pause and take the
         * notification's play button with it. Together with [playbackStateFor] this is what media3
         * derives `isPlaying` from, and it comes back out false while buffering because the state is
         * `STATE_BUFFERING` rather than because this said so.
         */
        internal fun playWhenReady(playing: Boolean, buffering: Boolean): Boolean =
            playing || buffering

        /**
         * A television's snapshot as a media3 playback state.
         *
         * `STATE_IDLE` is deliberately unreachable: it would mean a player with nothing prepared, and
         * `State` forbids both an error and `isLoading` outside it - so a snapshot mapped there would
         * throw rather than render.
         */
        internal fun playbackStateFor(buffering: Boolean, ended: Boolean): Int = when {
            ended -> Player.STATE_ENDED
            buffering -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }

        /**
         * Whether the track now playing can be seeked at all.
         *
         * **The gate is which resource is growing, not whether anything is.** A background transcode
         * of a track the user has already skipped past says nothing about the one they are listening
         * to, and greying the scrubber out for it would be wrong in the annoying direction.
         */
        internal fun seekable(growingResourceId: String?, currentResourceId: String?): Boolean =
            growingResourceId == null || growingResourceId != currentResourceId

        /**
         * The delegate's commands, minus the seeks, when the playing resource is still being encoded.
         *
         * Honest rather than restrictive: a stream with no stated length has nothing to answer a byte
         * range against, so a seek would fail rather than be slow. Removing the commands is also what
         * greys the scrubber out and drops the matching actions from the lockscreen and from Android
         * Auto, which no amount of refusing a seek afterwards would do.
         */
        internal fun seekCommands(base: Player.Commands, seekable: Boolean): Player.Commands {
            if (seekable) return base
            return base.buildUpon()
                .remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_BACK)
                .remove(Player.COMMAND_SEEK_FORWARD)
                .build()
        }

        /**
         * Where a snapshot's position had got to [elapsedMs] after it was true.
         *
         * A pure function of four numbers, which is the point: it is the only arithmetic in this class
         * and the one thing about a seek bar that can be pinned down without a television in the room.
         * Paused holds still, and [speed] scales the advance - a track at 2x whose anchor did not
         * account for it would have its bar corrected backwards on every snapshot.
         *
         * A negative [elapsedMs] is a clock that went backwards, and answers where playback was.
         */
        internal fun anchoredPositionMs(
            positionMs: Long,
            speed: Float,
            playing: Boolean,
            elapsedMs: Long,
        ): Long {
            if (!playing) return positionMs
            return positionMs + (elapsedMs.coerceAtLeast(0) * speed).toLong()
        }

        /**
         * Whether a reported position is a jump rather than ordinary progress.
         *
         * A pure function of two numbers, and the whole of the decision. Snapshots land twice a second
         * against an estimate that keeps running, so the two are never exactly equal even when nothing
         * happened; a window wide enough to cover that and narrow enough to catch the smallest scrub
         * the TV's remote can make - five seconds - is what tells them apart.
         */
        internal fun isDiscontinuity(reportedMs: Long, extrapolatedMs: Long): Boolean =
            abs(reportedMs - extrapolatedMs) > DISCONTINUITY_MS

        internal const val DISCONTINUITY_MS = 1_000L
    }
}

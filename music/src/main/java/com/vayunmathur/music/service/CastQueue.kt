package com.vayunmathur.music.service

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.data.MusicRepository
import com.vayunmathur.music.platform.CastPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "CastQueue"

/**
 * The relationship between the phone's queue and the television, on the service's main thread.
 *
 * [CastingPlayer] makes the phone's five transports describe and drive the television's player; this
 * is the other half - the handover in each direction, and the things only a queue owner can do. They
 * are separate because they answer to different objects: the wrapper is asked questions by media3 and
 * must answer synchronously, while everything here is a decision made in reaction to a change.
 *
 * **It drives the local [ExoPlayer] directly, not through the wrapper.** That is the point: pausing
 * and muting the phone must not be routed to the television, and seeking the phone back to where the
 * television got to must not be routed anywhere at all.
 */
class CastQueue(context: Context, private val local: ExoPlayer) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * The library by id, so a queue item can be turned back into something castable.
     *
     * Mirrored rather than queried per track change, and populated long before it is needed: this
     * object is built with the service, which exists because something is already playing, and a cast
     * cannot be started until the now-playing screen has a track on it.
     */
    private var library: Map<Long, Music> = emptyMap()

    private var casting = false

    /** The local player's own gain, restored when the cast ends. */
    private var localVolume = 1f

    /** What the television was last told to play, so an `ended` is attributed to the right item. */
    private var offered: String? = null

    /**
     * Whether the current `ended` has already been acted on.
     *
     * **Cleared by the first snapshot that is *not* ended, and by nothing else.** The obvious guard -
     * remembering which item was skipped - does not work: the television goes on reporting `ended` for
     * up to a heartbeat after the phone has moved on, because the next `PLAY_MEDIA` has an IPC hop, a
     * LAN round trip and possibly the start of a transcode to get through first. A guard re-armed by
     * the offer would see that stale snapshot against the *new* item and skip a track that never
     * played. Only the television saying it is no longer ended proves it has moved.
     */
    private var advancedOnEnded = false

    /** Where the television got to, and whether it meant to be playing, for handing playback back. */
    private var tvPositionMs = 0L
    private var tvPlaying = false

    /**
     * The one hook that makes the queue work.
     *
     * Whatever moved the local player - the app's own next button, a headset, a car, the auto-advance
     * in [onTvPlayback] - ends here, and the television is told to play what the phone has moved to. A
     * field rather than an anonymous object so [release] can undo what [init] did.
     */
    private val transitions = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (casting) offer(mediaItem, startPositionMs = 0)
        }
    }

    init {
        scope.launch {
            MusicRepository.get(app).music.collect { songs ->
                library = songs.associateBy { it.id }
            }
        }
        scope.launch { CastPlayback.state.collect { onCastState(it) } }
        scope.launch { CastPlayback.tv.collect { onTvPlayback(it) } }
        local.addListener(transitions)
    }

    fun release() {
        scope.cancel()
        local.removeListener(transitions)
    }

    private fun onCastState(state: CastPlayback.State) {
        val nowCasting = state is CastPlayback.State.Casting
        if (nowCasting == casting) return
        casting = nowCasting
        if (nowCasting) begin() else end()
    }

    /**
     * Hand playback to the television, keeping the place.
     *
     * Paused **and** muted, which is not belt and braces: pausing is what stops the sound now, and
     * muting is what keeps it stopped if anything starts the delegate again - an audio-focus change,
     * or a path into the local player this class has not thought of. A track coming out of the phone
     * and the television at once is the one failure a user cannot miss.
     */
    private fun begin() {
        localVolume = local.volume
        local.pause()
        local.volume = 0f
        tvPositionMs = local.currentPosition
        tvPlaying = true
        offer(local.currentMediaItem, startPositionMs = local.currentPosition)
    }

    /**
     * Take playback back, at the position the television had reached.
     *
     * Without the seek the phone rewinds to wherever it was paused, which is 0:00 if the cast began
     * with the track - straight after a notification that read 2:31.
     */
    private fun end() {
        offered = null
        advancedOnEnded = false
        local.volume = localVolume
        if (local.currentMediaItem != null) {
            local.seekTo(tvPositionMs)
            if (tvPlaying) local.play()
        }
    }

    private fun onTvPlayback(playback: CastPlayback.TvPlayback?) {
        val snapshot = playback?.state ?: return
        tvPositionMs = snapshot.positionMs
        // Buffering counts as playing here: it is what the user asked for, and a stall at the moment
        // they disconnect is not a reason to come back paused.
        tvPlaying = snapshot.playing || snapshot.buffering
        if (!snapshot.ended) {
            advancedOnEnded = false
            return
        }
        if (!casting || advancedOnEnded) return
        // Attributed to what the television was last told to play, so an `ended` that arrives before
        // anything was offered - a session that ended before a track loaded - moves nothing.
        if (offered == null) return
        advancedOnEnded = true
        if (local.hasNextMediaItem()) {
            // The transition this causes is what issues the next `PLAY_MEDIA`; nothing is sent here.
            local.seekToNextMediaItem()
        } else {
            Log.i(TAG, "the queue is finished; the television stays where it is")
        }
    }

    /**
     * Tell the television to play what the phone has moved to.
     *
     * A track the library has nothing for is dropped rather than guessed at: the resource id *is* the
     * media id, and Cast answers a request for anything this app did not offer with a `404`.
     */
    private fun offer(item: MediaItem?, startPositionMs: Long) {
        val resourceId = item?.mediaId ?: return
        val song = resourceId.toLongOrNull()?.let { library[it] }
        if (song == null) {
            Log.w(TAG, "nothing in the library for '$resourceId'; the TV was not told to play")
            return
        }
        offered = resourceId
        scope.launch { CastPlayback.play(app, song, startPositionMs) }
    }
}

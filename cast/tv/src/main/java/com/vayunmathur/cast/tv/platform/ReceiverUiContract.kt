package com.vayunmathur.cast.tv.platform

import com.vayunmathur.cast.protocol.PlaybackState

/** Where the receiver is, from the point of view of a screen. */
sealed interface ReceiverPhase {

    /** Loading the device identity and binding sockets. */
    data object Starting : ReceiverPhase

    /** Announced on the LAN, waiting for a phone. */
    data object Advertising : ReceiverPhase

    /**
     * A phone we do not know is connecting, and [code] is on screen for the user to type.
     *
     * [codeChanged] is set after three wrong attempts, when the old code was thrown away - the user
     * has to be told to look again rather than left retyping something that can no longer work.
     */
    data class Pairing(
        val senderName: String,
        val code: String,
        val attemptsLeft: Int,
        val codeChanged: Boolean = false,
    ) : ReceiverPhase

    /** Paired; the stream is being set up. */
    data class Connected(val senderName: String) : ReceiverPhase

    /** Frames are arriving. */
    data class Mirroring(
        val senderName: String,
        val width: Int,
        val height: Int,
        /**
         * The name of the app the frames are coming from, or empty when the phone is mirroring its
         * screen.
         *
         * Comes from `StreamConfig.appLabel`, which `:cast` fills in from the `callingPackage` the
         * framework attaches to its picker - never from anything the streaming app asserted about
         * itself.
         */
        val appLabel: String = "",
    ) : ReceiverPhase {
        /**
         * Who to name on screen: the app if there is one, otherwise the phone.
         *
         * "Receiving from YouPipe" is more use than "Receiving from Pixel 9" when it is YouPipe's
         * video on screen and not the phone's, and the phone's name is still the right answer for
         * screen mirroring.
         */
        val sourceName: String get() = appLabel.ifBlank { senderName }
    }

    /** Something the user has to know about, already a sentence rather than a code. */
    data class Failed(val reason: ReceiverFailure) : ReceiverPhase
}

/** The failures worth distinguishing, because each has a different answer. */
enum class ReceiverFailure {
    /** No hardware H.265 or AV1 decoder. Nothing about the phone or the network will help. */
    NoDecoder,

    /**
     * No Opus decoder, on a session that is nothing but audio.
     *
     * Its own failure rather than [NoDecoder]'s, because the answer is different and because the
     * alternative was the worst outcome available: before audio was negotiated at all, this TV
     * would have accepted the session and then sat in total silence with nothing on screen to say
     * why. A video session with no sound is still worth having, so this is reported only when sound
     * was the whole session.
     */
    NoAudioDecoder,

    /**
     * There is a decoder and somewhere to draw, and the codec settings it needed never arrived.
     *
     * Distinct from [NoDecoder] on purpose: both look like a black screen, and they send anyone
     * debugging one in opposite directions. This one means the phone's side of the AV1 handover did
     * not happen, and the phone has been told so it can use H.265 next time.
     */
    MissingCodecConfig,

    /** The phone sealed a secret to an identity that is not ours, or spoke a version we do not. */
    Handshake,

    /** The stream started and then stopped - the phone went away, or the link did. */
    StreamEnded,
}

/**
 * Everything the TV's own screens draw.
 *
 * Every field is defaulted so the idle screen can be rendered from the parts it cares about, which is
 * the same discipline `:cast`'s `CastUiState` follows.
 */
data class ReceiverUiState(
    val phase: ReceiverPhase = ReceiverPhase.Starting,
    /** What the phone will show in its device list, and what the idle screen tells the user to pick. */
    val deviceName: String = "",
    /**
     * Android 16+ refused the mDNS registration. Distinct from "nothing has connected yet", because
     * the fix is a permission rather than opening the app on the phone.
     */
    val localNetworkBlocked: Boolean = false,
    /**
     * The last thing the phone said about its playback, or null if it has never said anything.
     *
     * **Beside [phase] rather than inside [ReceiverPhase.Mirroring], deliberately.** A phase change
     * replaces the whole object, and playback outlives several of them - the display mode settling,
     * a surface being handed back after a resize. Nesting it would throw the seek bar's anchor away
     * every time something unrelated happened.
     *
     * Null is also the gate on the whole feature: screen mirroring never sends a snapshot, so an
     * overlay that only exists when this is non-null cannot appear over a mirrored phone screen.
     */
    val playback: PlaybackSnapshot? = null,
)

/**
 * One playback snapshot, with the moment it landed.
 *
 * **The wall clock is the missing half of the message.** The phone reports twice a second and the
 * panel redraws sixty times a second, so a bar plotted at the reported position would step visibly.
 * What makes it smooth is knowing *when* the number was true, and the interpolation itself lives in
 * `:cast:protocol` beside the message - a pure function of a snapshot and an elapsed time, which is
 * the one part of this feature provable without a television.
 *
 * The TV never computes position authoritatively and cannot: what it holds is a 150 ms RTP jitter
 * buffer, which is a smoothing device and not a content clock. Every fresh snapshot re-anchors, so
 * error is bounded by one reporting interval rather than accumulating.
 */
data class PlaybackSnapshot(
    val state: PlaybackState,
    val receivedAtMs: Long,
) {
    fun positionAt(nowMs: Long): Long = state.interpolated(nowMs - receivedAtMs)
}

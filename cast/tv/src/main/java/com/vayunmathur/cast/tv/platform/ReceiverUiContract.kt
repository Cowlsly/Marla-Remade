package com.vayunmathur.cast.tv.platform

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
    ) : ReceiverPhase

    /** Something the user has to know about, already a sentence rather than a code. */
    data class Failed(val reason: ReceiverFailure) : ReceiverPhase
}

/** The failures worth distinguishing, because each has a different answer. */
enum class ReceiverFailure {
    /** No H.264 decoder. Nothing about the phone or the network will help. */
    NoDecoder,

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
)

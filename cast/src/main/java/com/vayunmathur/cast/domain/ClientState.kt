package com.vayunmathur.cast.domain

import com.vayunmathur.cast.protocol.Negotiation

/** How far along the handshake a session is. */
enum class ClientPhase {
    /** No socket, or the TV closed it. */
    Idle,

    /** Connecting and exchanging identities. */
    Connecting,

    /** The TV is showing six digits and waiting for them. Nothing proceeds until the user types them. */
    AwaitingCode,

    /** Paired. The stream can be configured. */
    Paired,

    /** Frames are going out. */
    Streaming,

    /** Gave up, and [ClientState.failure] says why. */
    Failed,
}

/** Why a session could not be established, as a cause rather than a sentence. */
enum class ClientFailure {
    /** The TV would not accept a TCP connection, or it went away mid-handshake. */
    Unreachable,

    /** The TV speaks a protocol version this build does not. */
    VersionMismatch,

    /** The TV sent something that is not a valid handshake for the state it was in. */
    Protocol,

    /** The code was wrong, and the TV has thrown it away and shown a new one. */
    CodeRejected,

    /** The TV refused to start a stream. */
    StreamRefused,
}

/**
 * Everything the session knows, as one value.
 *
 * [attemptsLeft] is only meaningful in [ClientPhase.AwaitingCode]; it is what lets the phone say "two
 * tries left" rather than making the user find out by failing.
 */
data class ClientState(
    val phase: ClientPhase = ClientPhase.Idle,
    val receiverName: String? = null,
    val attemptsLeft: Int = 0,
    /** True after three wrong codes: the TV is showing a *new* code, so the old one is worthless. */
    val codeChanged: Boolean = false,
    val failure: ClientFailure? = null,
    /** Set once `STREAM_READY` has come back, which is what the media pipeline is built from. */
    val negotiation: Negotiation? = null,
)

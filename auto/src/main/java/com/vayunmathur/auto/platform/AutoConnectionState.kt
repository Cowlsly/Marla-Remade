package com.vayunmathur.auto.platform

/** What the phone is currently doing about a car, as the status screen needs to render it. */
sealed interface AutoConnectionState {
    /** No head unit attached, over either transport. */
    data object Disconnected : AutoConnectionState

    /** A transport is up; version negotiation and the TLS handshake are in progress. */
    data object Connecting : AutoConnectionState

    /** Authenticated and projecting. [carName] is whatever the head unit reported. */
    data class Projecting(val carName: String) : AutoConnectionState

    /** The head unit refused our certificate, or auth otherwise failed. */
    data object Rejected : AutoConnectionState
}

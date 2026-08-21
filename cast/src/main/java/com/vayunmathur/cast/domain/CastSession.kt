package com.vayunmathur.cast.domain

/** How far along the CONNECT -> LAUNCH -> join sequence the session is. */
enum class CastPhase {
    /** No channel, or the receiver closed it. */
    Idle,

    /** CONNECT and LAUNCH are on the wire; waiting for a RECEIVER_STATUS naming the app. */
    Launching,

    /** The app is running and joined: [CastSessionState.transportId] is addressable. */
    Ready,

    /** The receiver refused, and [CastSessionState.failure] says why. */
    Failed,
}

/** Everything the session knows, as one value. */
data class CastSessionState(
    val phase: CastPhase = CastPhase.Idle,
    val sessionId: String? = null,
    val transportId: String? = null,
    val volumeLevel: Double = 1.0,
    val muted: Boolean = false,
    val failure: String? = null,
)

/**
 * The CastV2 sequencing rules, with no I/O.
 *
 * Every method returns the frames to put on the wire and mutates [state]; nothing here
 * touches a socket, a Context or a coroutine, which is what makes the ordering testable on
 * the JVM. The ordering is the part that is easy to get wrong:
 *
 *  1. CONNECT to [RECEIVER_ID], then LAUNCH the app id.
 *  2. The RECEIVER_STATUS that comes back names a `sessionId` **and** a `transportId`.
 *  3. CONNECT again, to that `transportId` - a frame sent to the app before this is dropped
 *     without an error.
 *  4. Only then is the `webrtc` namespace addressable, which is where mirroring is negotiated.
 *
 * `requestId` is a monotonic counter starting at 1 (zero means "no response wanted" and
 * would lose the correlation), and every response echoes it.
 *
 * [appId] is what gets launched and what a RECEIVER_STATUS is matched against. It comes from
 * [MirroringAppIds.forKind] and is not a free choice: a receiver refuses the wrong one outright.
 */
class CastSession(private val appId: String) {

    var state: CastSessionState = CastSessionState()
        private set

    private var nextRequestId = 1

    fun allocateRequestId(): Int = nextRequestId++

    /** CONNECT to the platform receiver and launch the mirroring receiver on it. */
    fun open(): List<CastFrame> {
        state = CastSessionState(phase = CastPhase.Launching)
        return listOf(
            frame(CastNamespaces.CONNECTION, RECEIVER_ID, CastSimpleMessage("CONNECT")),
            frame(CastNamespaces.RECEIVER, RECEIVER_ID, LaunchRequest(allocateRequestId(), appId)),
        )
    }

    /**
     * The keepalive. The receiver drops a channel that has been quiet for about 10 seconds,
     * so this has to be sent on a timer whether or not anything is being mirrored.
     */
    fun heartbeat(): CastFrame =
        frame(CastNamespaces.HEARTBEAT, RECEIVER_ID, CastSimpleMessage("PING"))

    /**
     * Feed one received frame in; get the replies to send back out.
     *
     * Anything unrecognised is ignored rather than treated as an error - receivers send status
     * types and namespaces this app has no interest in, and a speaker emits a steady stream of
     * `com.google.cast.multizone` traffic that is none of our business.
     */
    fun onMessage(namespace: String, payload: String): List<CastFrame> {
        val envelope = runCatching { CastJson.decodeFromString<CastEnvelope>(payload) }.getOrNull()
            ?: return emptyList()
        return when (namespace) {
            CastNamespaces.HEARTBEAT ->
                if (envelope.type == "PING") {
                    listOf(frame(CastNamespaces.HEARTBEAT, RECEIVER_ID, CastSimpleMessage("PONG")))
                } else {
                    emptyList()
                }
            CastNamespaces.CONNECTION -> {
                if (envelope.type == "CLOSE") onClosed()
                emptyList()
            }
            CastNamespaces.RECEIVER -> onReceiverMessage(envelope, payload)
            else -> emptyList()
        }
    }

    /**
     * Volume is the *receiver's* volume, so it goes to [RECEIVER_ID] on the receiver namespace
     * rather than to the running app.
     */
    fun setVolume(level: Double): List<CastFrame> {
        val clamped = level.coerceIn(0.0, 1.0)
        state = state.copy(volumeLevel = clamped)
        return listOf(
            frame(
                CastNamespaces.RECEIVER,
                RECEIVER_ID,
                SetVolumeRequest(allocateRequestId(), CastVolume(level = clamped)),
            ),
        )
    }

    fun setMuted(muted: Boolean): List<CastFrame> {
        state = state.copy(muted = muted)
        return listOf(
            frame(
                CastNamespaces.RECEIVER,
                RECEIVER_ID,
                SetVolumeRequest(allocateRequestId(), CastVolume(muted = muted)),
            ),
        )
    }

    /**
     * Tear the session down: stop the receiver app, then CLOSE the channel.
     *
     * Stopping the app is what returns the TV to its backdrop; closing without it leaves the
     * mirroring receiver on screen with the last frame frozen.
     */
    fun close(): List<CastFrame> {
        val frames = buildList {
            state.sessionId?.let {
                add(
                    frame(
                        CastNamespaces.RECEIVER,
                        RECEIVER_ID,
                        StopSessionRequest(allocateRequestId(), it),
                    ),
                )
            }
            state.transportId?.let {
                add(frame(CastNamespaces.CONNECTION, it, CastSimpleMessage("CLOSE")))
            }
            add(frame(CastNamespaces.CONNECTION, RECEIVER_ID, CastSimpleMessage("CLOSE")))
        }
        onClosed()
        return frames
    }

    private fun onClosed() {
        state = CastSessionState(phase = CastPhase.Idle)
    }

    private fun onReceiverMessage(envelope: CastEnvelope, payload: String): List<CastFrame> {
        if (envelope.type == "LAUNCH_ERROR") {
            state = state.copy(
                phase = CastPhase.Failed,
                failure = envelope.reason ?: envelope.type,
            )
            return emptyList()
        }
        if (envelope.type != "RECEIVER_STATUS") return emptyList()
        val status = runCatching { CastJson.decodeFromString<ReceiverStatusMessage>(payload) }
            .getOrNull()?.status ?: return emptyList()
        status.volume?.let { volume ->
            state = state.copy(
                volumeLevel = volume.level ?: state.volumeLevel,
                muted = volume.muted ?: state.muted,
            )
        }
        val app = status.applications.firstOrNull {
            it.appId == appId && it.transportId.isNotEmpty()
        }
        if (app == null) {
            // The app is gone - either someone else took the receiver over or STOP landed.
            // Not a failure, but there is nothing left to mirror to.
            if (state.phase == CastPhase.Ready) {
                state = state.copy(
                    phase = CastPhase.Idle,
                    sessionId = null,
                    transportId = null,
                )
            }
            return emptyList()
        }
        // A relaunch produces a new transportId, so join on change rather than on phase:
        // reusing the old one is how a session ends up connected to an app that has exited.
        if (state.phase == CastPhase.Ready && state.transportId == app.transportId) {
            return emptyList()
        }
        state = state.copy(
            phase = CastPhase.Ready,
            sessionId = app.sessionId,
            transportId = app.transportId,
            failure = null,
        )
        return listOf(
            frame(CastNamespaces.CONNECTION, app.transportId, CastSimpleMessage("CONNECT")),
        )
    }

    private inline fun <reified T> frame(
        namespace: String,
        destinationId: String,
        payload: T,
    ): CastFrame = CastFrame(namespace, destinationId, CastJson.encodeToString(payload))
}

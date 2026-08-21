package com.vayunmathur.cast.protocol

import com.vayunmathur.e2ee.E2ee
import java.io.DataInput
import java.io.EOFException
import java.util.Base64

/**
 * `int32be(len) ‖ payload` on a TCP stream.
 *
 * The same idiom the old `CastChannel` used, minus its permissive TLS - we have our own handshake,
 * so there is no PKI problem to work around and no reason for a socket in this app to trust
 * anything it is handed.
 */
object ControlFraming {

    /**
     * A frame larger than this is refused rather than allocated.
     *
     * `TV_IDENTITY` carries a Base64 ML-KEM + ML-DSA bundle, a few kilobytes, so this is orders of
     * magnitude of headroom; the point is only that a corrupt or hostile length prefix cannot ask
     * for a gigabyte.
     */
    const val MAX_FRAME_BYTES = 1 shl 20

    fun encode(body: ByteArray): ByteArray {
        val out = ByteArray(4 + body.size)
        out[0] = (body.size ushr 24).toByte()
        out[1] = (body.size ushr 16).toByte()
        out[2] = (body.size ushr 8).toByte()
        out[3] = body.size.toByte()
        body.copyInto(out, 4)
        return out
    }

    /**
     * One frame's body, or null at a clean end of stream.
     *
     * Throws on a length prefix outside the bound: the stream cannot be resynchronised after one, so
     * carrying on would read a payload as a length forever.
     */
    fun read(input: DataInput): ByteArray? {
        val length = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        require(length in 1..MAX_FRAME_BYTES) { "refusing a $length byte control frame" }
        return ByteArray(length).also { input.readFully(it) }
    }
}

/**
 * Turns [ControlMessage]s into frame bodies and back, encrypting once the session key is known.
 *
 * The cipher is installed by [useSessionKey] at exactly one point in the sequence - immediately
 * after `SEALED_SECRET` - on both ends. That is what keeps them in step: there is no per-frame flag
 * saying whether a body is encrypted, because a flag an attacker can clear is a downgrade.
 *
 * AES-256-GCM via `E2ee.aesEncrypt`, so the control channel *is* authenticated even though the media
 * payload is not. A tampered control frame fails its tag and closes the session, which is why
 * unauthenticated media cannot be used to hijack one.
 */
class ControlCodec {

    private var sessionKey: ByteArray? = null

    /** True once [useSessionKey] has been called, i.e. from `PAIR_REQUIRED` onward. */
    val isEncrypting: Boolean get() = sessionKey != null

    fun useSessionKey(key: ByteArray) {
        sessionKey = key
    }

    /** The frame body for [message]: JSON, then encrypted if the key is installed. */
    fun encode(message: ControlMessage): ByteArray {
        val json = ControlJson.encodeToString(message).toByteArray(Charsets.UTF_8)
        val key = sessionKey ?: return json
        return E2ee.aesEncrypt(key, json)
    }

    /**
     * The message in [body], or null when it is not one.
     *
     * Null covers a failed GCM tag, a payload that is not JSON, and a `type` this build does not
     * know. All three are handled the same way by both ends - close the session - because after the
     * cipher is installed there is no benign reason for any of them.
     */
    fun decode(body: ByteArray): ControlMessage? {
        val key = sessionKey
        val json = if (key == null) {
            body
        } else {
            runCatching { E2ee.aesDecrypt(key, body) }.getOrNull() ?: return null
        }
        return runCatching {
            ControlJson.decodeFromString<ControlMessage>(json.toString(Charsets.UTF_8))
        }.getOrNull()
    }
}

/**
 * Base64 for the byte fields the handshake carries as strings.
 *
 * `java.util.Base64` rather than `android.util.Base64` so the handshake round-trips in a plain JVM
 * test, which is the whole verification strategy while there is no TV to try it on.
 */
object ProtocolBase64 {
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun decode(text: String): ByteArray? = runCatching { Base64.getDecoder().decode(text) }.getOrNull()
}

package com.vayunmathur.passwords.cable

import android.util.Log
import com.vayunmathur.passwords.util.Cbor

/**
 * Drives one caBLE v2 authenticator session end-to-end, matching the Chromium `TunnelTransport`
 * flow (`//device/fido/cable/v2_authenticator.cc`):
 *
 *  1. derive EID key + tunnel id from the QR secret;
 *  2. open the tunnel "new" endpoint and learn the routing id;
 *  3. build + BLE-advertise the encrypted EID; derive the Noise PSK from the plaintext EID;
 *  4. run the Noise `KNpsk0` handshake (responder);
 *  5. send the post-handshake message (getInfo + features), padded and encrypted;
 *  6. relay CTAP2 traffic (with the [MessageType] framing) until a getAssertion completes.
 *
 * Verified against Chromium `//device/fido/cable/` source (2024).
 */
class CableSession(
    private val qr: CableQrData,
    private val ctapProcessor: CtapProcessor,
    private val advertiser: CableAdvertiser,
    private val onStatus: (String) -> Unit = {},
) {
    private var tunnel: CableTunnel? = null

    /** Runs the session to completion. Returns true if a getAssertion was answered successfully. */
    suspend fun run(): Boolean {
        try {
            val eidKey = CableKeys.eidKey(qr.qrSecret)
            val tunnelId = CableKeys.tunnelId(qr.qrSecret)

            val domainId = TunnelDomains.DEFAULT_ID
            val domain = TunnelDomains.decode(domainId)
            onStatus("Connecting to tunnel $domain")
            val tun = CableTunnel.connectNew(domain, tunnelId).also { tunnel = it }

            val routingId = tun.routingId ?: ByteArray(CableEid.ROUTING_ID_SIZE)
            if (tun.routingId == null) Log.w(TAG, "No routing id from tunnel; using zeros (browser likely won't connect back)")
            val nonce = CableEid.randomNonce()
            val plaintextEid = CableEid.buildPlaintext(nonce, routingId, domainId)
            onStatus("Advertising over Bluetooth")
            advertiser.start(CableEid.encrypt(plaintextEid, eidKey)) { ok ->
                if (!ok) Log.w(TAG, "BLE advertising unavailable; proximity check may fail")
            }

            // PSK binds the handshake to the advertised EID.
            val psk = CableKeys.psk(qr.qrSecret, plaintextEid)

            onStatus("Performing secure handshake")
            Log.d(TAG, "Waiting for browser handshake (msg1)...")
            val responder = NoiseResponder(P256.decodePoint(qr.peerPublicKey), psk)
            val msg1 = tun.receive()
            Log.d(TAG, "Received handshake msg1: ${msg1.size} bytes")
            responder.readMessage1(msg1)
            val (msg2, crypter) = responder.writeMessage2()
            tun.send(msg2)
            Log.d(TAG, "Handshake complete; sent msg2 (${msg2.size} bytes)")

            // Post-handshake message: { 1: getInfo bytes, 3: features }. Sent as PLAIN CBOR, which
            // makes the desktop negotiate protocol revision 1 (MessageType-byte framing below).
            // (Padding it here would select revision 0, which omits the type byte.)
            val postHandshake = Cbor.encode(linkedMapOf<Any, Any>(
                1L to CtapGetInfoResponse().encode(),
                3L to listOf("ctap"),
            ))
            tun.send(crypter.encrypt(postHandshake))
            Log.d(TAG, "Sent post-handshake getInfo")

            onStatus("Waiting for sign-in request")
            return ctapLoop(tun, crypter)
        } catch (e: Exception) {
            Log.e(TAG, "caBLE session error", e)
            onStatus("Sign-in failed")
            return false
        } finally {
            advertiser.stop()
            tunnel?.close()
        }
    }

    private suspend fun ctapLoop(tun: CableTunnel, crypter: Crypter): Boolean {
        while (true) {
            val plain = crypter.decrypt(tun.receive())
            if (plain.isEmpty()) continue
            Log.d(TAG, "Transport message: type=${plain[0].toInt() and 0xFF}, ${plain.size} bytes")

            val messageType = plain[0].toInt() and 0xFF
            val payload = plain.copyOfRange(1, plain.size)
            when (messageType) {
                MSG_SHUTDOWN -> {
                    onStatus("Cancelled by the other device")
                    return false
                }
                MSG_CTAP -> {
                    val response = ctapProcessor.process(payload)
                    tun.send(crypter.encrypt(byteArrayOf(MSG_CTAP.toByte()) + response))
                    val isAssertion = payload.isNotEmpty() &&
                        (payload[0].toInt() and 0xFF) == Ctap.CMD_GET_ASSERTION
                    if (isAssertion && response.isNotEmpty() && response[0].toInt() == Ctap.OK) {
                        onStatus("Sign-in complete")
                        return true
                    }
                }
                else -> Unit // kUpdate / kJSON: ignore for v1
            }
        }
    }

    companion object {
        private const val TAG = "CableSession"

        // caBLE MessageType (v2_constants.h).
        private const val MSG_SHUTDOWN = 0
        private const val MSG_CTAP = 1
    }
}

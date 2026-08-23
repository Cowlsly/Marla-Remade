package com.vayunmathur.cast.protocol

import java.net.InetAddress
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the session-pinned TLS the media proxy runs on.
 *
 * The certificate is assembled from hand-written DER, so the first thing to establish is that it
 * is a certificate at all: [java.security.cert.CertificateFactory] parsing it and the signature
 * verifying against its own key together catch every structural mistake, and a mistake here would
 * otherwise show up as an unexplained handshake failure on a TV.
 *
 * The second thing is that the pin is load-bearing in both directions. A trust manager that
 * accepts the right certificate but also accepts a wrong one is the failure that matters, because
 * it looks exactly like success.
 */
class EphemeralTlsTest {

    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    @Test
    fun `mints a certificate that parses and carries its own valid signature`() {
        val certificate = EphemeralTls.server(listOf(loopback)).certificate
        // Self-signed, so its own public key is the one that has to verify it. This is the check
        // that fails if any length prefix in the hand-written DER is wrong.
        certificate.verify(certificate.publicKey)
        certificate.checkValidity()
        assertEquals(3, certificate.version, "extensions require a v3 certificate")
        assertTrue(certificate.serialNumber.signum() > 0, "a negative serial number is malformed")
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
        assertTrue(certificate.subjectX500Principal.name.contains("Modern Apps cast"))
    }

    @Test
    fun `is already valid when a TV's clock is behind ours`() {
        // A TV that has been unplugged can come up hours behind. Anything in the stack that
        // checks dates would reject a certificate stamped in what it believes is the future.
        val certificate = EphemeralTls.server(listOf(loopback)).certificate
        assertTrue(
            certificate.notBefore.toInstant().isBefore(java.time.Instant.now().minusSeconds(3_600)),
            "the certificate must already be valid by an hour at least",
        )
    }

    @Test
    fun `names every address it was given so the hostname check can pass`() {
        // The TV dials an IP address. Without an iPAddress SAN the pin would accept the
        // certificate and the hostname check would then reject it, which no amount of pinning
        // fixes and which reads as an unrelated failure.
        val address = InetAddress.getByName("192.168.0.82")
        val names = assertNotNull(
            EphemeralTls.server(listOf(loopback, address)).certificate.subjectAlternativeNames,
            "a certificate with no subject alternative names cannot be used for a bare IP",
        )
        val ips = names.filter { it[0] == 7 }.map { it[1] as String }
        assertTrue(ips.contains("192.168.0.82"), "got $ips")
        assertTrue(ips.contains(loopback.hostAddress), "got $ips")
    }

    @Test
    fun `marks itself for server authentication`() {
        val usage = EphemeralTls.server(listOf(loopback)).certificate.extendedKeyUsage
        assertEquals(listOf("1.3.6.1.5.5.7.3.1"), usage)
    }

    @Test
    fun `fingerprints the DER encoding, which is what the TV is told to pin`() {
        val credentials = EphemeralTls.server(listOf(loopback))
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(credentials.certificate.encoded)
        assertContentEquals(expected, credentials.fingerprint)
        assertEquals(32, credentials.fingerprint.size)
    }

    @Test
    fun `mints a different key every session`() {
        // The certificate is thrown away with the session, so two sessions sharing a key would
        // mean a fingerprint from one session still worked against the next.
        val first = EphemeralTls.server(listOf(loopback))
        val second = EphemeralTls.server(listOf(loopback))
        assertTrue(!first.fingerprint.contentEquals(second.fingerprint))
    }

    @Test
    fun `a client pinned to the fingerprint completes the handshake`() {
        val credentials = EphemeralTls.server(listOf(loopback))
        withServer(credentials) { port ->
            val socket = EphemeralTls.client(credentials.fingerprint)
                .socketFactory.createSocket(loopback, port) as SSLSocket
            socket.use {
                it.soTimeout = 5_000
                it.startHandshake()
                it.outputStream.write(41)
                it.outputStream.flush()
                assertEquals(42, it.inputStream.read(), "the connection handshook but carried nothing")
            }
        }
    }

    @Test
    fun `a client pinned to another fingerprint refuses to talk`() {
        val credentials = EphemeralTls.server(listOf(loopback))
        val otherFingerprint = EphemeralTls.server(listOf(loopback)).fingerprint
        withServer(credentials) { port ->
            val socket = EphemeralTls.client(otherFingerprint)
                .socketFactory.createSocket(loopback, port) as SSLSocket
            socket.use {
                it.soTimeout = 5_000
                assertFailsWith<SSLException> { it.startHandshake() }
            }
        }
    }

    @Test
    fun `a client trusting the platform's authorities refuses an ephemeral certificate`() {
        // Confirms the certificate is genuinely untrusted without the pin - if the default trust
        // manager accepted it, the pin would be decoration.
        val credentials = EphemeralTls.server(listOf(loopback))
        withServer(credentials) { port ->
            val socket = javax.net.ssl.SSLContext.getDefault()
                .socketFactory.createSocket(loopback, port) as SSLSocket
            socket.use {
                it.soTimeout = 5_000
                assertFailsWith<SSLException> { it.startHandshake() }
            }
        }
    }

    /**
     * Runs a one-shot echo server on the credentials, for the duration of [body].
     *
     * The server thread swallows its exceptions on purpose: the tests that assert a refusal cause
     * one here too, and the assertion belongs on the client side where the pin is.
     */
    private fun withServer(credentials: EphemeralTls.ServerCredentials, body: (Int) -> Unit) {
        val server = credentials.sslContext.serverSocketFactory.createServerSocket(0, 1, loopback)
        val thread = Thread {
            runCatching {
                server.accept().use { client ->
                    client.soTimeout = 5_000
                    val byte = client.inputStream.read()
                    if (byte != -1) {
                        client.outputStream.write(byte + 1)
                        client.outputStream.flush()
                    }
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        try {
            body(server.localPort)
        } finally {
            runCatching { server.close() }
            thread.join(2_000)
        }
    }
}

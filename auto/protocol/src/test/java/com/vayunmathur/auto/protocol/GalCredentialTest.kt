package com.vayunmathur.auto.protocol

import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Exercises the shipped GAL credential without a device.
 *
 * The PEMs are read straight out of `src/main/assets` rather than duplicated into test
 * resources, so this fails if the shipped files are wrong rather than passing against a
 * private copy.
 */
class GalCredentialTest {

    private val assetDir = File("src/main/assets/gal")

    private fun asset(name: String): String {
        val file = File(assetDir, name)
        assertTrue(file.exists(), "missing shipped asset ${file.absolutePath}")
        return file.readText()
    }

    private fun context() = GalCredential.create(
        certPem = asset("client-cert.pem"),
        keyPem = asset("client-key.pem"),
        rootPem = asset("root.pem"),
    )

    @Test
    fun `the shipped leaf chains to the shipped GAL root`() {
        val factory = CertificateFactory.getInstance("X.509")
        val leaf = factory.generateCertificate(
            asset("client-cert.pem").byteInputStream(),
        ) as X509Certificate
        val root = factory.generateCertificate(
            asset("root.pem").byteInputStream(),
        ) as X509Certificate

        val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("GAL", root)
        }
        val trust = TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(anchors) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

        // Throws if the chain does not validate.
        trust.checkClientTrusted(arrayOf(leaf, root), "RSA")
        assertEquals("CarService", leaf.subjectX500Principal.name.substringAfter("O=")
            .substringBefore(","))
    }

    @Test
    fun `the engine is a server that demands a client certificate`() {
        val engine = GalCredential.serverEngine(context())
        assertEquals(false, engine.useClientMode, "the phone is the TLS server, not the client")
        assertTrue(engine.needClientAuth, "the head unit must present a GAL-signed cert")
    }

    @Test
    fun `a mutually authenticated handshake completes`() {
        val context = context()
        val server = GalCredential.serverEngine(context)
        // A head unit presents its own GAL-signed certificate; we only have the one, which
        // is exactly what openauto does on the head-unit side.
        val client = context.createSSLEngine().apply { useClientMode = true }

        handshake(server, client)

        assertEquals("TLSv1.2", server.session.protocol)
        val peer = server.session.peerCertificates.first() as X509Certificate
        assertTrue(
            peer.subjectX500Principal.name.contains("CarService"),
            "server should have authenticated the client, saw ${peer.subjectX500Principal}",
        )
    }

    /** Drives two engines against each other in memory until both report FINISHED. */
    private fun handshake(server: SSLEngine, client: SSLEngine) {
        server.beginHandshake()
        client.beginHandshake()

        val packetSize = maxOf(server.session.packetBufferSize, client.session.packetBufferSize)
        val appSize =
            maxOf(server.session.applicationBufferSize, client.session.applicationBufferSize)
        val empty = ByteBuffer.allocate(0)

        repeat(MAX_STEPS) {
            if (settled(server) && settled(client)) return
            var progressed = false
            for ((from, to) in listOf(client to server, server to client)) {
                runTasks(from)
                if (from.handshakeStatus != HandshakeStatus.NEED_WRAP) continue

                val wire = ByteBuffer.allocate(packetSize)
                val wrapped = from.wrap(empty, wire)
                assertEquals(Status.OK, wrapped.status, "wrap failed")
                wire.flip()
                progressed = true

                while (wire.hasRemaining()) {
                    runTasks(to)
                    val plain = ByteBuffer.allocate(appSize)
                    val unwrapped = to.unwrap(wire, plain)
                    assertEquals(Status.OK, unwrapped.status, "unwrap failed")
                    if (to.handshakeStatus == HandshakeStatus.NEED_WRAP) break
                }
            }
            if (!progressed) fail("handshake stalled: ${server.handshakeStatus} / ${client.handshakeStatus}")
        }
        fail("handshake did not settle within $MAX_STEPS steps")
    }

    private fun runTasks(engine: SSLEngine) {
        while (engine.handshakeStatus == HandshakeStatus.NEED_TASK) {
            engine.delegatedTask?.run() ?: break
        }
    }

    private fun settled(engine: SSLEngine) =
        engine.handshakeStatus == HandshakeStatus.NOT_HANDSHAKING ||
            engine.handshakeStatus == HandshakeStatus.FINISHED

    private companion object {
        const val MAX_STEPS = 50
    }
}

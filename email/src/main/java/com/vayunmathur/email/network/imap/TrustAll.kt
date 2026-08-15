package com.vayunmathur.email.imap

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.net.Socket

/**
 * Trust handling for raw IMAP/SMTP sockets.
 *
 * Known hosts (gmail, yahoo, aol, fastmail, outlook) use the system default
 * trust store (strict). Custom hosts use a permissive trust manager that
 * accepts any certificate (mirrors the old Jakarta `ssl.trust=*` behavior
 * for self-hosted servers).
 */
object TrustAll {

    private val TRUST_ALL_MANAGERS: Array<TrustManager> = arrayOf(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    )

    private val permissiveFactory: SSLSocketFactory by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, TRUST_ALL_MANAGERS, SecureRandom())
        ctx.socketFactory
    }

    private val systemFactory: SSLSocketFactory by lazy {
        SSLSocketFactory.getDefault() as SSLSocketFactory
    }

    fun permissiveSocketFactory(): SSLSocketFactory = permissiveFactory
    fun systemSocketFactory(): SSLSocketFactory = systemFactory

    /** Known good hosts where we want strict system trust. */
    fun isKnownHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "imap.gmail.com" || h.endsWith(".gmail.com") ||
            h == "smtp.gmail.com" ||
            h == "imap.mail.yahoo.com" || h == "smtp.mail.yahoo.com" ||
            h == "imap.aol.com" || h == "smtp.aol.com" ||
            h == "imap.fastmail.com" || h == "smtp.fastmail.com" ||
            h == "imap.mail.me.com" || h == "smtp.mail.me.com" ||
            h == "outlook.office365.com" || h == "smtp-mail.outlook.com" ||
            h == "smtp.office365.com" || h == "outlook.office.com" ||
            h == "imap-mail.outlook.com" || h == "imap.outlook.com"
    }

    fun socketFactoryFor(host: String, forceTrustAll: Boolean): SSLSocketFactory {
        return if (forceTrustAll || !isKnownHost(host)) permissiveFactory else systemFactory
    }

    fun createSocket(host: String, port: Int, trustAll: Boolean): Socket {
        val factory = socketFactoryFor(host, trustAll)
        return factory.createSocket(host, port)
    }

    fun createPlainSocket(host: String, port: Int): Socket = Socket(host, port)

    fun upgradeToTls(
        plainSocket: Socket,
        host: String,
        port: Int,
        trustAll: Boolean,
        autoClose: Boolean = true,
    ): SSLSocket {
        val factory = socketFactoryFor(host, trustAll)
        val ssl = factory.createSocket(plainSocket, host, port, autoClose) as SSLSocket
        ssl.useClientMode = true
        ssl.startHandshake()
        return ssl
    }
}

package com.vayunmathur.email.network.imap

import java.io.IOException

/**
 * Thrown when IMAP or SMTP authentication fails (LOGIN, PLAIN, LOGIN, XOAUTH2).
 * Replaces `jakarta.mail.AuthenticationFailedException` after Jakarta removal.
 */
class ImapAuthException(message: String, cause: Throwable? = null) : IOException(message, cause)

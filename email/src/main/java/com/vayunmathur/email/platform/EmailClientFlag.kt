package com.vayunmathur.email

/**
 * Raw IMAP/SMTP is now permanent — legacy Jakarta Mail path removed in Phase 5.
 * All transport goes through [com.vayunmathur.email.imap.ImapClient] and
 * [com.vayunmathur.email.smtp.SmtpClient].
 */
object EmailClientFlag {
    /** Always true — raw socket clients are the only implementation. */
    const val USE_RAW = true

    /** When true, raw clients trust all certs for custom hosts (system trust for known hosts). */
    const val RAW_TRUST_ALL_CUSTOM = true
}

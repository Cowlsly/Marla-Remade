package org.schabi.newpipe.extractor.services.youtube.sabrng.exception

/** Indicates that YouTube rejected or could not complete the current attestation identity. */
class SabrAttestationException : SabrProtocolException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

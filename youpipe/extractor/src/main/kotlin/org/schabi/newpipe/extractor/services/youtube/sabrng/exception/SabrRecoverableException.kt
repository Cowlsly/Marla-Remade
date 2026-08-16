package org.schabi.newpipe.extractor.services.youtube.sabrng.exception

/** A SABR protocol error the session may recover from by re-requesting media. */
class SabrRecoverableException : SabrProtocolException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

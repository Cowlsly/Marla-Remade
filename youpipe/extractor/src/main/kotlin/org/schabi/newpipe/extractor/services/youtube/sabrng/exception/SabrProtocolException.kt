package org.schabi.newpipe.extractor.services.youtube.sabrng.exception

import org.schabi.newpipe.extractor.exceptions.ExtractionException

/** Signals that the SABR wire protocol or control flow entered an unexpected state. */
open class SabrProtocolException : ExtractionException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

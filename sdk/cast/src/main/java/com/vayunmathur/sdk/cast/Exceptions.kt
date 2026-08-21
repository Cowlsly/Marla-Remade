package com.vayunmathur.sdk.cast

/** Anything the SDK refuses outright. */
open class CastException(message: String) : Exception(message)

/** The Cast app is not installed, so there is nothing to broker through. */
class CastNotInstalledException :
    CastException("MA Cast is not installed")

/** Cast is installed but predates [CastContract.MIN_CAST_VERSION_CODE]. */
class CastNeedsUpdateException :
    CastException("MA Cast is too old to stream app content")

/**
 * The permission was refused, which for a signature permission means the caller is not signed with
 * the Modern Apps key.
 */
class CastPermissionException :
    CastException("Not permitted to bind the Cast content service")

/** No TV is connected. The picker has to be launched and completed first. */
class CastNoSessionException :
    CastException("No TV session; launch the picker first")

/** Cast could not start a stream - negotiation or the encoder failed. */
class CastSessionFailedException(reason: Int) :
    CastException("Cast could not start a session (reason $reason)")

package com.vayunmathur.logviewer.platform

import androidx.annotation.StringRes
import com.vayunmathur.logviewer.domain.LogDocument

/**
 * The outcome of trying to build a document from an intent.
 *
 * The distinction between the two failures is deliberate and matches the app being replaced:
 * a malformed or unauthorised intent closes the window without saying anything, because there is
 * nothing the user did and nothing they can do. Only the one case they *asked* for - "More info",
 * where the tombstone turned out to be unreadable - tells them so.
 */
internal sealed interface LogLoadResult {

    data class Loaded(val document: LogDocument) : LogLoadResult

    data class Unavailable(@param:StringRes val messageRes: Int? = null) : LogLoadResult
}

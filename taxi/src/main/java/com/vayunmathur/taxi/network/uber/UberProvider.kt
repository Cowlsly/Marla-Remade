package com.vayunmathur.taxi.network.uber

import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.QuoteResult
import com.vayunmathur.taxi.data.RideQuote
import com.vayunmathur.taxi.platform.deeplink.RideDeepLinks
import com.vayunmathur.taxi.provider.RideProvider

/**
 * Uber fares via the web GraphQL endpoint (`uber-re/api-notes.md` §4).
 *
 * The native `/rt/` API is deliberately not used: it signs requests with `libse_loader.so` and
 * attests with Play Integrity, neither of which is available on a degoogled device.
 *
 * The web endpoint is reachable and cookie-authenticated, but its fare **operation name and
 * variables are not yet known** — Apollo introspection is disabled server-side, so they have to
 * come out of a real session via [UberWebView]'s capture hook. Until that has been run once,
 * this reports honestly rather than guessing at an operation.
 */
class UberProvider(private val context: android.content.Context) : RideProvider {
    override val provider = Provider.UBER

    override suspend fun isSignedIn(): Boolean =
        UberSession(context.applicationContext).isSignedIn()

    override suspend fun quotes(pickup: Place, dropoff: Place): QuoteResult {
        if (!isSignedIn()) return QuoteResult.NotSignedIn
        return QuoteResult.Failed(
            "Signed in, but the fare query hasn't been captured yet — " +
                "open Uber under Settings and request a ride to record it",
        )
    }

    override fun bookingUri(pickup: Place, dropoff: Place, quote: RideQuote?): String =
        RideDeepLinks.webUri(Provider.UBER, pickup, dropoff, quote)
}

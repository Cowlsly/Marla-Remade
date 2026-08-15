package com.vayunmathur.taxi.platform.deeplink

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.RideQuote

/**
 * Builds the "open the provider with this trip pre-filled" links.
 *
 * Parameter names were read as string literals out of each production APK — see
 * `uber-re/api-notes.md` §5 and `lyft-re/api-notes.md`. The one exception is Lyft's `id`
 * (ride type), which comes from Lyft's published deep-link format rather than a literal we
 * recovered, so a wrong value degrades to "no ride type preselected" rather than a broken link.
 *
 * Web URLs are the default because the target device is degoogled and will not have the
 * official apps installed; [openBooking] upgrades to the app scheme only when the package
 * actually resolves.
 */
object RideDeepLinks {

    fun webUri(provider: Provider, pickup: Place, dropoff: Place, quote: RideQuote?): String =
        when (provider) {
            Provider.UBER -> uber(base = "https://m.uber.com/ul/", pickup, dropoff, quote)
            Provider.LYFT -> lyft(base = "https://lyft.com/ride", pickup, dropoff, quote)
        }

    fun appUri(provider: Provider, pickup: Place, dropoff: Place, quote: RideQuote?): String =
        when (provider) {
            Provider.UBER -> uber(base = "uber://", pickup, dropoff, quote)
            Provider.LYFT -> lyft(base = "lyft://ridetype", pickup, dropoff, quote)
        }

    private fun uber(base: String, pickup: Place, dropoff: Place, quote: RideQuote?): String =
        base.toUri().buildUpon().apply {
            appendQueryParameter("action", "setPickup")
            appendQueryParameter("pickup[latitude]", pickup.location.latitude.toString())
            appendQueryParameter("pickup[longitude]", pickup.location.longitude.toString())
            appendQueryParameter("pickup[nickname]", pickup.name)
            appendQueryParameter("dropoff[latitude]", dropoff.location.latitude.toString())
            appendQueryParameter("dropoff[longitude]", dropoff.location.longitude.toString())
            appendQueryParameter("dropoff[nickname]", dropoff.name)
            quote?.let { appendQueryParameter("product_id", it.productId) }
        }.build().toString()

    private fun lyft(base: String, pickup: Place, dropoff: Place, quote: RideQuote?): String =
        base.toUri().buildUpon().apply {
            appendQueryParameter("id", quote?.productId ?: "lyft")
            appendQueryParameter("pickup[latitude]", pickup.location.latitude.toString())
            appendQueryParameter("pickup[longitude]", pickup.location.longitude.toString())
            pickup.address?.let { appendQueryParameter("pickup[address]", it) }
            appendQueryParameter("destination[latitude]", dropoff.location.latitude.toString())
            appendQueryParameter("destination[longitude]", dropoff.location.longitude.toString())
            dropoff.address?.let { appendQueryParameter("destination[address]", it) }
        }.build().toString()

    /**
     * Opens the trip in the official app when it is installed, otherwise on the provider's
     * mobile web. Returns false when neither could be launched.
     */
    fun openBooking(
        context: Context,
        provider: Provider,
        pickup: Place,
        dropoff: Place,
        quote: RideQuote?,
    ): Boolean {
        val candidates = buildList {
            if (isInstalled(context, provider.packageName)) {
                add(appUri(provider, pickup, dropoff, quote))
            }
            add(webUri(provider, pickup, dropoff, quote))
        }
        for (uri in candidates) {
            val intent = Intent(Intent.ACTION_VIEW, uri.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    private fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }.isSuccess
}

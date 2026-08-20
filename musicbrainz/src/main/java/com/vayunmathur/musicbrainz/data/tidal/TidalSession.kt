package com.vayunmathur.musicbrainz.data.tidal

import android.content.Context
import com.vayunmathur.musicbrainz.platform.MusicBrainzPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Hands out a valid Tidal access token, refreshing it when it is about to expire.
 *
 * An album downloads as one worker per track, all running at once, so without the [mutex]
 * they would each notice the same near-expiry token and fire off a wave of refreshes. The
 * lock funnels that to one: the losers re-read the token the winner just persisted.
 */
class TidalSession(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = MusicBrainzPrefs(appContext)

    /** A usable access token, or null when there is no session or the refresh failed. */
    suspend fun accessToken(): String? = mutex.withLock {
        val account = prefs.tidalAccount.first() ?: return null
        if (account.expiresAtMs > System.currentTimeMillis() + EXPIRY_MARGIN_MS &&
            account.accessToken.isNotBlank()
        ) {
            return account.accessToken
        }
        val refresh = account.refreshToken?.takeIf { it.isNotBlank() }
            ?: return account.accessToken.ifBlank { null }
        val refreshed = TidalAuth.refresh(refresh) ?: return account.accessToken.ifBlank { null }
        prefs.updateTidalTokens(refreshed.accessToken, refreshed.refreshToken, refreshed.expiresAtMs)
        refreshed.accessToken.ifBlank { null }
    }

    /** The account's country code, needed on nearly every Tidal API call. */
    suspend fun countryCode(): String? = prefs.tidalAccount.first()?.countryCode?.ifBlank { null }

    private companion object {
        // Refresh a little early so a token cannot expire mid-download.
        const val EXPIRY_MARGIN_MS = 60_000L
        val mutex = Mutex()
    }
}

package com.vayunmathur.taxi.data.lyft

import android.content.Context
import com.vayunmathur.taxi.network.lyft.LyftToken
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.lyftDataStore by preferencesDataStore(name = "lyft_session")

class LyftTokenStore(private val context: Context) {
    private val accessKey = stringPreferencesKey("access_token")
    private val refreshKey = stringPreferencesKey("refresh_token")
    private val expiresAtKey = longPreferencesKey("expires_at")
    private val userIdKey = stringPreferencesKey("user_id")

    suspend fun save(token: LyftToken) {
        context.lyftDataStore.edit {
            it[accessKey] = token.accessToken
            if (!token.refreshToken.isNullOrBlank()) it[refreshKey] = token.refreshToken
            it[expiresAtKey] = System.currentTimeMillis() + token.expiresIn * 1000
            if (!token.userId.isNullOrBlank()) it[userIdKey] = token.userId
        }
    }

    suspend fun accessToken(): String? =
        context.lyftDataStore.data.first()[accessKey]?.takeIf { it.isNotBlank() }

    suspend fun refreshToken(): String? =
        context.lyftDataStore.data.first()[refreshKey]?.takeIf { it.isNotBlank() }

    suspend fun userId(): String? = context.lyftDataStore.data.first()[userIdKey]

    /** Treats the token as expired a minute early so a call never races the expiry. */
    suspend fun isExpired(): Boolean {
        val expiresAt = context.lyftDataStore.data.first()[expiresAtKey] ?: return true
        return System.currentTimeMillis() >= expiresAt - 60_000
    }

    suspend fun isSignedIn(): Boolean = accessToken() != null

    suspend fun clear() {
        context.lyftDataStore.edit { it.clear() }
    }
}

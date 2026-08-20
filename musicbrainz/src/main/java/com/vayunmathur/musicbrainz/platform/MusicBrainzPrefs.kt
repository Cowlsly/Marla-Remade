package com.vayunmathur.musicbrainz.platform

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.musicBrainzDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "musicbrainz")

/**
 * A signed-in Tidal account.
 *
 * Lives here rather than in `data.tidal` because `TidalSession` reads it from these prefs,
 * so declaring it alongside its store keeps the dependency one-way.
 */
@Serializable
data class TidalAccount(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
    val countryCode: String,
    val userId: String,
    val username: String,
)

/** App settings: where downloads go, and whether lyrics are fetched alongside them. */
class MusicBrainzPrefs(context: Context) {
    private val appContext = context.applicationContext

    val musicFolder: Flow<String?> = appContext.musicBrainzDataStore.data.map { it[FOLDER_URI] }
    val fetchLyrics: Flow<Boolean> =
        appContext.musicBrainzDataStore.data.map { it[FETCH_LYRICS] ?: true }
    val embedCoverArt: Flow<Boolean> =
        appContext.musicBrainzDataStore.data.map { it[EMBED_COVER] ?: true }

    /** The signed-in Tidal account, or null when signed out. */
    val tidalAccount: Flow<TidalAccount?> =
        appContext.musicBrainzDataStore.data.map { it[TIDAL_ACCOUNT]?.toAccount() }

    val downloadSource: Flow<DownloadSource> = appContext.musicBrainzDataStore.data.map {
        it[DOWNLOAD_SOURCE].toEnum(DownloadSource.entries, DownloadSource.YouTube)
    }

    val tidalQuality: Flow<TidalQuality> = appContext.musicBrainzDataStore.data.map {
        it[TIDAL_QUALITY].toEnum(TidalQuality.entries, TidalQuality.High)
    }

    suspend fun musicFolderUri(): String? = musicFolder.first()

    suspend fun setMusicFolder(uri: String) {
        appContext.musicBrainzDataStore.edit { it[FOLDER_URI] = uri }
    }

    suspend fun clearMusicFolder() {
        appContext.musicBrainzDataStore.edit { it.remove(FOLDER_URI) }
    }

    suspend fun setFetchLyrics(value: Boolean) {
        appContext.musicBrainzDataStore.edit { it[FETCH_LYRICS] = value }
    }

    suspend fun setEmbedCoverArt(value: Boolean) {
        appContext.musicBrainzDataStore.edit { it[EMBED_COVER] = value }
    }

    suspend fun setTidalAccount(account: TidalAccount) {
        appContext.musicBrainzDataStore.edit { it[TIDAL_ACCOUNT] = json.encodeToString(account) }
    }

    suspend fun clearTidalAccount() {
        appContext.musicBrainzDataStore.edit { it.remove(TIDAL_ACCOUNT) }
    }

    /**
     * Replaces the tokens of the stored account, keeping who it belongs to.
     *
     * Read and write happen inside one [edit] so two workers refreshing at once cannot
     * write over each other's tokens.
     */
    suspend fun updateTidalTokens(accessToken: String, refreshToken: String?, expiresAtMs: Long) {
        appContext.musicBrainzDataStore.edit { prefs ->
            val existing = prefs[TIDAL_ACCOUNT]?.toAccount() ?: return@edit
            prefs[TIDAL_ACCOUNT] = json.encodeToString(
                existing.copy(
                    accessToken = accessToken,
                    refreshToken = refreshToken ?: existing.refreshToken,
                    expiresAtMs = expiresAtMs,
                ),
            )
        }
    }

    suspend fun setDownloadSource(source: DownloadSource) {
        appContext.musicBrainzDataStore.edit { it[DOWNLOAD_SOURCE] = source.name }
    }

    suspend fun setTidalQuality(quality: TidalQuality) {
        appContext.musicBrainzDataStore.edit { it[TIDAL_QUALITY] = quality.name }
    }

    private companion object {
        val FOLDER_URI = stringPreferencesKey("music_folder_uri")
        val FETCH_LYRICS = booleanPreferencesKey("fetch_lyrics")
        val EMBED_COVER = booleanPreferencesKey("embed_cover_art")
        val TIDAL_ACCOUNT = stringPreferencesKey("tidal_account")
        val DOWNLOAD_SOURCE = stringPreferencesKey("download_source")
        val TIDAL_QUALITY = stringPreferencesKey("tidal_quality")

        val json = Json { ignoreUnknownKeys = true }

        /** A value this version cannot read means signed out, which is recoverable. */
        fun String.toAccount(): TidalAccount? =
            runCatching { json.decodeFromString<TidalAccount>(this) }.getOrNull()

        /** Enums are stored by name, so a rename falls back to the default instead of crashing. */
        fun <T : Enum<T>> String?.toEnum(values: List<T>, default: T): T =
            values.firstOrNull { it.name == this } ?: default
    }
}

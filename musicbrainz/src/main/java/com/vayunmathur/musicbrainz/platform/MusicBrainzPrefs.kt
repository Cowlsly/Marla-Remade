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

private val Context.musicBrainzDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "musicbrainz")

/** App settings: where downloads go, and whether lyrics are fetched alongside them. */
class MusicBrainzPrefs(context: Context) {
    private val appContext = context.applicationContext

    val musicFolder: Flow<String?> = appContext.musicBrainzDataStore.data.map { it[FOLDER_URI] }
    val fetchLyrics: Flow<Boolean> =
        appContext.musicBrainzDataStore.data.map { it[FETCH_LYRICS] ?: true }
    val embedCoverArt: Flow<Boolean> =
        appContext.musicBrainzDataStore.data.map { it[EMBED_COVER] ?: true }

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

    private companion object {
        val FOLDER_URI = stringPreferencesKey("music_folder_uri")
        val FETCH_LYRICS = booleanPreferencesKey("fetch_lyrics")
        val EMBED_COVER = booleanPreferencesKey("embed_cover_art")
    }
}

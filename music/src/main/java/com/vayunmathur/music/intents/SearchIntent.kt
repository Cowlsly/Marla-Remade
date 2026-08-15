package com.vayunmathur.music.intents

import com.vayunmathur.library.intents.music.MusicSearchResult
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.music.data.MusicRepository
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
class SearchIntent: AssistantIntent<String, List<MusicSearchResult>>(serializer<String>(), serializer<List<MusicSearchResult>>()) {

    override suspend fun performCalculation(input: String): List<MusicSearchResult> {
        val repo = MusicRepository.get(this)
        return listOf(
            repo.getAllMusic().filter { it.title.contains(input, ignoreCase = true) }.map { MusicSearchResult(it.id, it.title, "song") },
            repo.getAllAlbums().filter { it.name.contains(input, ignoreCase = true) }.map { MusicSearchResult(it.id, it.name, "album") },
            repo.getAllArtists().filter { it.name.contains(input, ignoreCase = true) }.map { MusicSearchResult(it.id, it.name, "artist") },
            repo.getAllPlaylists().filter { it.name.contains(input, ignoreCase = true) }.map { MusicSearchResult(it.id, it.name, "playlist") },
        ).flatten()
    }
}

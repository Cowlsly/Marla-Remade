package com.vayunmathur.music.data
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.music.platform.MusicViewModel
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class Album(
    @PrimaryKey(autoGenerate = true) override val id: Long,
    val name: String,
    val uri: String
): DatabaseItem {
    @Composable
    fun artistString(musicViewModel: MusicViewModel): String {
        val artistIDs by musicViewModel.matchedArtistsForAlbum(id)
        val artists by musicViewModel.artists.collectAsState()

        return remember(artistIDs, artists) {
            when {
                artistIDs.size > 2 -> "Various Artists"
                artistIDs.isEmpty() -> ""
                else -> artistIDs.mapNotNull { artistId ->
                    artists.find { it.id == artistId }?.name
                }.joinToString()
            }
        }
    }
}

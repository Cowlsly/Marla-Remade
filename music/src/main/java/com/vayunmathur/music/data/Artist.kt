package com.vayunmathur.music.data
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable

/** See [Album]: read from `MediaStore.Audio.Artists` on every refresh, never persisted. */
@Serializable
data class Artist(
    override val id: Long,
    val name: String,
    val uri: String
): DatabaseItem
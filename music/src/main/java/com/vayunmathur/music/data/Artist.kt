package com.vayunmathur.music.data
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class Artist(
    @PrimaryKey(autoGenerate = true) override val id: Long,
    val name: String,
    val uri: String
): DatabaseItem
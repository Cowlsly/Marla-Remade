package com.vayunmathur.findfamily.data

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
@Entity(indices = [Index(value = ["userid", "timestamp"])])
data class LocationValue(
    val userid: Long,
    @Embedded val coord: Coord,
    val speed: Float,
    val acc: Float,
    @ColumnInfo(index = true)
    val timestamp: Instant,
    val battery: Float,
    @PrimaryKey(autoGenerate = true) override val id: Long = 0
): DatabaseItem {
    fun toCompatible(senderPlatform: String? = null): LocationValueCompatible {
        return LocationValueCompatible(
            userid = userid.toULong(),
            coord = coord,
            speed = speed,
            acc = acc,
            timestamp = timestamp.toEpochMilliseconds(),
            battery = battery,
            sleep = false,
            id = id.toULong(),
            senderPlatform = senderPlatform
        )
    }
}

@Serializable
data class LocationValueCompatible(
    val id: ULong = 0uL,
    val userid: ULong,
    val coord: Coord,
    val speed: Float,
    val acc: Float,
    val timestamp: Long,
    val battery: Float,
    val sleep: Boolean? = null,
    /** Sender's platform tag (`"android"` or `"ios"`). Optional for backward compatibility. */
    val senderPlatform: String? = null
) {
    fun toLocationValue(): LocationValue {
        return LocationValue(
            userid = userid.toLong(),
            coord = coord,
            speed = speed,
            acc = acc,
            timestamp = Instant.fromEpochMilliseconds(timestamp),
            battery = battery,
            // NOT id = id.toLong(): `id` is the *sender's* local autogenerate primary
            // key. Reusing it here makes @Upsert collide across devices (every device's
            // ids grow from 1 in lockstep), so peers overwrite each other's — and your
            // own — rows by PK, leaving getLatest() with stale data. Use 0 so Room
            // assigns a fresh local id and each received fix is stored as its own row.
            id = 0,
        )
    }
}
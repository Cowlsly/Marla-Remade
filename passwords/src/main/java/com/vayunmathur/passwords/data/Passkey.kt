package com.vayunmathur.passwords.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem

@Entity
data class Passkey(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val rpId: String = "",
    val rpName: String = "",
    val credentialId: String = "",
    val userId: String = "",
    val userName: String = "",
    val userDisplayName: String = "",
    val privateKeyBytes: ByteArray = ByteArray(0),
    val creationTime: Long = System.currentTimeMillis(),
    val lastUsedTime: Long = System.currentTimeMillis(),
    val signCount: Int = 0,
    val syncId: String = newSyncId(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DatabaseItem {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Passkey) return false
        return id == other.id &&
            rpId == other.rpId &&
            credentialId == other.credentialId &&
            userId == other.userId
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + rpId.hashCode()
        result = 31 * result + credentialId.hashCode()
        result = 31 * result + userId.hashCode()
        return result
    }
}

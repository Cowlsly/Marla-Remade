package com.vayunmathur.passwords.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem

/**
 * [Passkey.linkedPasswordSyncId] value meaning the user detached this passkey by hand. A syncId is
 * 32 lowercase hex chars (see [newSyncId]), so this can never collide with a real one.
 */
const val PASSKEY_LINK_DETACHED = "-"

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
    /**
     * Which password this passkey belongs to: null/blank to match automatically, a password's
     * syncId to pin it there, or [PASSKEY_LINK_DETACHED] to keep it standalone. Decode it via
     * `PasskeyLink` rather than reading it directly. Holds the syncId and not the row id because
     * ids are per-device and the vault syncs.
     */
    val linkedPasswordSyncId: String? = null,
    val syncId: String = newSyncId(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DatabaseItem {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Passkey) return false
        return id == other.id &&
            rpId == other.rpId &&
            credentialId == other.credentialId &&
            userId == other.userId &&
            // StateFlow conflates by equality, so a link-only change would never reach the UI if
            // this were left out.
            linkedPasswordSyncId == other.linkedPasswordSyncId
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + rpId.hashCode()
        result = 31 * result + credentialId.hashCode()
        result = 31 * result + userId.hashCode()
        result = 31 * result + linkedPasswordSyncId.hashCode()
        return result
    }
}

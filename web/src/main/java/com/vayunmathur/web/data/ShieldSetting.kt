package com.vayunmathur.web.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.web.domain.ShieldLevel
import com.vayunmathur.web.domain.ShieldsSettings

/**
 * Per-site shields override, keyed by host. Null fields inherit the global setting, so a
 * site that only turns off cosmetic filtering still tracks later changes to the others.
 * Host is the primary key — shields apply per registrable site, not per origin, matching
 * Brave's own panel. Not a DatabaseItem.
 */
@Entity
data class ShieldSetting(
    @PrimaryKey val host: String,
    val level: String? = null,
    val blockTrackers: Boolean? = null,
    val cosmeticFiltering: Boolean? = null,
    val fingerprintProtection: Boolean? = null,
    val httpsUpgrade: Boolean? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toSettings(): ShieldsSettings = ShieldsSettings(
        level = level?.let { runCatching { ShieldLevel.valueOf(it) }.getOrNull() },
        blockTrackers = blockTrackers,
        cosmeticFiltering = cosmeticFiltering,
        fingerprintProtection = fingerprintProtection,
        httpsUpgrade = httpsUpgrade,
    )

    companion object {
        fun from(host: String, settings: ShieldsSettings) = ShieldSetting(
            host = host,
            level = settings.level?.name,
            blockTrackers = settings.blockTrackers,
            cosmeticFiltering = settings.cosmeticFiltering,
            fingerprintProtection = settings.fingerprintProtection,
            httpsUpgrade = settings.httpsUpgrade,
        )
    }
}

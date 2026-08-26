package com.vayunmathur.web.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * Saved installed site / PWA backing for "Add to Home screen" pinned shortcuts.
 * String PK keeps shortcutId stable. Not a DatabaseItem.
 */
@Entity
data class InstalledSite(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val shortName: String = "",
    val iconUrl: String? = null,
    val faviconUrl: String? = null,
    val themeColor: String? = null,
    val backgroundColor: String? = null,
    val displayMode: String = "standalone",
    val startUrl: String = url,
    val origin: String,
    val installedAt: Long = System.currentTimeMillis(),
)

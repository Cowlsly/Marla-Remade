package com.vayunmathur.games.voxels.platform

import com.vayunmathur.library.util.BaseBackupAgent

class AppBackupAgent : BaseBackupAgent() {
    override val prefNames: List<String> get() = listOf("voxels_prefs")

    // The shared DataStoreUtils file, holding AchievementsManager unlocks and VoxelsSync state.
    // Saved worlds live in filesDir/worlds and stay out of the cloud payload deliberately; see
    // res/xml/data_extraction_rules.xml.
    override val datastoreNames: List<String> get() = listOf("datastore_default")
}

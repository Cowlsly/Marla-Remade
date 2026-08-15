package com.vayunmathur.games.voxels.util

import com.vayunmathur.library.util.BaseBackupAgent

class AppBackupAgent : BaseBackupAgent() {
    override val prefNames: List<String> get() = listOf("voxels_prefs")
}

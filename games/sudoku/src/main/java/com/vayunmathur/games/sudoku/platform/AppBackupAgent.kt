package com.vayunmathur.games.sudoku.platform

import com.vayunmathur.library.util.BaseBackupAgent

class AppBackupAgent : BaseBackupAgent() {
    override val prefNames: List<String> get() = listOf("sudoku_stats")

    // The shared DataStoreUtils file, holding AchievementsManager unlocks.
    override val datastoreNames: List<String> get() = listOf("datastore_default")
}

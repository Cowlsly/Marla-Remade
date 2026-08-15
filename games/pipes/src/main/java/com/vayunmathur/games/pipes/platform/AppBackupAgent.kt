package com.vayunmathur.games.pipes.platform

import com.vayunmathur.library.util.BaseBackupAgent

class AppBackupAgent : BaseBackupAgent() {
    override val prefNames: List<String>
        get() = listOf("level_stats", "daily_stats")
}

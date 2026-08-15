package com.vayunmathur.games.wordmaker.platform

import com.vayunmathur.library.util.BaseBackupAgent

class AppBackupAgent : BaseBackupAgent() {
    override val datastoreNames: List<String>
        get() = listOf("settings")
}

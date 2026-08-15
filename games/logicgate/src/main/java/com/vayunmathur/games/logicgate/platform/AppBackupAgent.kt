package com.vayunmathur.games.logicgate.platform

import com.vayunmathur.library.util.BaseBackupAgent

class AppBackupAgent : BaseBackupAgent() {
    override val prefNames: List<String>
        get() = listOf("logicgate_stats")
    // datastore_default is the actual file from DataStoreUtils.getInstance (datastore_default.preferences_pb)
    // logicgate_circuits_v1 is a *key* inside that file, not a separate datastore file — listing bogus names causes backup/restore crash
    override val datastoreNames: List<String>
        get() = listOf("datastore_default")
}

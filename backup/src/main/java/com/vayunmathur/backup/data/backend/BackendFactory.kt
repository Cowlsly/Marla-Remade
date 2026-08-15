package com.vayunmathur.backup.data.backend

import android.content.Context
import android.net.Uri
import com.vayunmathur.backup.data.BackendType
import com.vayunmathur.backup.data.BackupSettings

/** Builds the active [BackupBackend] from persisted [BackupSettings]. */
object BackendFactory {
    fun create(context: Context, settings: BackupSettings): BackupBackend? =
        when (settings.backendType) {
            BackendType.NONE -> null
            BackendType.SAF -> settings.safTreeUri
                ?.takeIf { it.isNotBlank() }
                ?.let { SafBackend(context.applicationContext, Uri.parse(it)) }
            BackendType.WEBDAV -> settings.webdavUrl
                .takeIf { it.isNotBlank() }
                ?.let {
                    WebDavBackend(it, settings.webdavUser, settings.webdavPassword)
                }
        }
}

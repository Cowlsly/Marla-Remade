package com.vayunmathur.backup.transport

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Framework-bound host for [ConfigurableBackupTransport]. The platform's
 * BackupManagerService binds this (via the `android.backup.TRANSPORT_HOST` action,
 * gated by the system-only BIND_BACKUP_TRANSPORT permission) and talks to the
 * transport through the binder returned here.
 *
 * Live behavior requires a platform-signed priv-app install whitelisted for
 * android.permission.BACKUP; it cannot be exercised on a stock device.
 */
class ConfigurableBackupTransportService : Service() {
    private val transport by lazy { ConfigurableBackupTransport(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = transport.binder
}

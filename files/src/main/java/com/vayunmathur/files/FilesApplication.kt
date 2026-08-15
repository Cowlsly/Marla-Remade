package com.vayunmathur.files

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import com.vayunmathur.files.data.saf.DocumentPickerActivity

/**
 * Toggles the SAF [DocumentPickerActivity] on exactly when Files is acting as the system
 * documents UI.
 *
 * The picker is declared `android:enabled="false"` so a normal (F-Droid / userspace) install
 * behaves identically to before — it never shows up in `GET_CONTENT` choosers and holds no
 * privileged role. When Files is shipped as the privileged system documents UI (MAOS), the
 * platform grants it `MANAGE_DOCUMENTS`; we detect that here and enable the component so it can
 * service `OPEN_DOCUMENT` / `CREATE_DOCUMENT` / `OPEN_DOCUMENT_TREE` / `GET_CONTENT`.
 *
 * The call is idempotent, so running it on every process start is cheap.
 */
class FilesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        syncDocumentPickerAvailability()
    }

    private fun syncDocumentPickerAvailability() {
        val privileged = checkSelfPermission(android.Manifest.permission.MANAGE_DOCUMENTS) ==
            PackageManager.PERMISSION_GRANTED
        val target = if (privileged) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val component = ComponentName(this, DocumentPickerActivity::class.java)
        if (packageManager.getComponentEnabledSetting(component) != target) {
            packageManager.setComponentEnabledSetting(
                component,
                target,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}

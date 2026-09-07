package com.vayunmathur.logviewer.intents

/**
 * The intent contract this app shares with the platform.
 *
 * **These strings are the wire format and must match
 * `frameworks/base/core/java/android/ext/LogViewerApp.java` exactly.** The framework is the
 * producer: `SystemErrorNotification`, `TombstoneHandler` and `DropBoxMonitor` all build their
 * intents through `LogViewerApp.createBaseErrorReportIntent()`, which sets the action and calls
 * `setPackage(PACKAGE_NAME)`. If a name here disagrees with the one there, nothing errors - the
 * activity opens and the report is simply empty.
 *
 * The three actions are derived from the package name and so changed with the rename, which is why
 * `LogViewerApp.PACKAGE_NAME` is patched to `com.vayunmathur.logviewer` in
 * `vendor/modern-apps/patches/frameworks_base.patch`. The extras are **bare short strings** and did
 * not change - do not "correct" them to package-qualified names.
 */
object LogViewerIntents {

    const val PACKAGE_NAME = "com.vayunmathur.logviewer"

    const val ACTION_ERROR_REPORT = "$PACKAGE_NAME.ERROR_REPORT"
    const val ACTION_LOGCAT = "$PACKAGE_NAME.LOGCAT"
    const val ACTION_PKG_LOGCAT = "$PACKAGE_NAME.PKG_LOGCAT"

    const val EXTRA_ERROR_TYPE = "type"
    const val EXTRA_GZIPPED_MESSAGE = "gzipped_msg"
    const val EXTRA_SOURCE_APP_INFO = "source_app_info"
    const val EXTRA_SHOW_REPORT_BUTTON = "show_report_button"
    const val EXTRA_TEXT_TOMBSTONE_FILE_PATH = "text_tombstone_file_path"
    const val EXTRA_TEXT_TOMBSTONE_LAST_MODIFIED_TIME = "text_tombstone_last_modified"
    const val EXTRA_PREFER_TEXT_TOMBSTONE = "prefer_text_tombstone"
}

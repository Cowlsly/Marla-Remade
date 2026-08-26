package com.vayunmathur.library.ui

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri

/** Stored in place of a URI when the user picks "Silent"; `null` means the system default. */
const val RINGTONE_SILENT = "silent"

/**
 * The name to show for a stored ringtone choice.
 *
 * [type] is a [RingtoneManager] `TYPE_*` constant, and only matters for `null` — "the system
 * default" is a different sound for a call than for an alarm.
 */
fun ringtoneTitle(
    context: Context,
    uriString: String?,
    type: Int = RingtoneManager.TYPE_RINGTONE,
): String = when (uriString) {
    null -> defaultRingtoneTitle(context, type)
    RINGTONE_SILENT -> context.getString(R.string.ringtone_silent)
    else -> runCatching {
        RingtoneManager.getRingtone(context, uriString.toUri())?.getTitle(context)
    }.getOrNull() ?: context.getString(R.string.ringtone_custom)
}

/**
 * "Default (Marimba)" rather than a bare "Default".
 *
 * Something with no sound of its own plays whatever the system is set to, and naming that is
 * the difference between the row telling the user something and telling them nothing. Resolved
 * on every read, so changing the system sound shows up here without the row being touched.
 */
private fun defaultRingtoneTitle(context: Context, type: Int): String {
    val default = context.getString(R.string.ringtone_default)
    val name = runCatching {
        RingtoneManager.getDefaultUri(type)
            ?.let { RingtoneManager.getRingtone(context, it)?.getTitle(context) }
    }.getOrNull()
    if (name.isNullOrBlank()) return default
    return context.getString(R.string.ringtone_default_named, default, name)
}

/** @param type a [RingtoneManager] `TYPE_*` constant, which decides the list the picker shows. */
fun ringtonePickerIntent(existing: String?, type: Int, title: String): Intent =
    Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, type)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, title)
        val current: Uri? = when (existing) {
            null -> RingtoneManager.getDefaultUri(type)
            RINGTONE_SILENT -> null
            else -> existing.toUri()
        }
        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
    }

fun ringtonePickerResult(data: Intent?): String {
    val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
        data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
    }
    return uri?.toString() ?: RINGTONE_SILENT
}

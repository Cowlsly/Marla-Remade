package com.vayunmathur.library.ui

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri

/** Stored in place of a URI when the user picks "Silent"; `null` means the system default. */
const val RINGTONE_SILENT = "silent"

fun ringtoneTitle(context: Context, uriString: String?): String = when (uriString) {
    null -> context.getString(R.string.ringtone_default)
    RINGTONE_SILENT -> context.getString(R.string.ringtone_silent)
    else -> runCatching {
        RingtoneManager.getRingtone(context, uriString.toUri())?.getTitle(context)
    }.getOrNull() ?: context.getString(R.string.ringtone_custom)
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

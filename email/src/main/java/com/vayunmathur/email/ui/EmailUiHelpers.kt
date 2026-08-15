package com.vayunmathur.email.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.email.R
import com.vayunmathur.email.data.UnsubscribeMethod
import com.vayunmathur.email.platform.MessageThreadActions
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.AppMessages
import androidx.core.net.toUri
import com.vayunmathur.library.ui.R as UiR

    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "attachment"

private fun uriSize(context: android.content.Context, uri: android.net.Uri): Long =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        }
    }.getOrNull() ?: 0L

/** Epoch millis for [hour]:00 today (or tomorrow if that time already passed, or sameDay=false forces next day). */
private fun scheduleTime(hour: Int, sameDay: Boolean): Long {
    val c = java.util.Calendar.getInstance()
    c.set(java.util.Calendar.HOUR_OF_DAY, hour)
    c.set(java.util.Calendar.MINUTE, 0)
    c.set(java.util.Calendar.SECOND, 0)
    c.set(java.util.Calendar.MILLISECOND, 0)
    if (!sameDay || c.timeInMillis <= System.currentTimeMillis()) {
        c.add(java.util.Calendar.DAY_OF_YEAR, 1)
    }
    return c.timeInMillis
}

/** Append an email to a comma-separated recipient field, avoiding duplicates. */
private fun appendRecipient(field: String, email: String): String {
    val existing = field.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (existing.any { it.equals(email, ignoreCase = true) }) return field
    return if (existing.isEmpty()) email else existing.joinToString(", ") + ", " + email
}

/** Read the email address from a contact-picker result URI (granted per-item, no permission needed). */
private fun contactEmail(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS),
            null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

/**
 * Split a plain-text body into (visibleText, quotedText). Quoted text starts at
 * the first reply boundary ("On … wrote:", "-----Original Message-----") or a
 * run of '>'-prefixed lines. Returns empty quotedText when nothing is quoted.
 */
private fun splitQuotedText(body: String): Pair<String, String> {
    val lines = body.split("\n")
    val onWrote = Regex("^On .+ wrote:\\s*$")
    val origMsg = Regex("^-{2,}\\s*Original Message\\s*-{2,}\\s*$", RegexOption.IGNORE_CASE)
    for (i in lines.indices) {
        val line = lines[i].trim()
        val isBoundary = onWrote.matches(line) || origMsg.matches(line) || line.startsWith(">")
        if (isBoundary && i > 0) {
            val main = lines.subList(0, i).joinToString("\n").trimEnd()
            val quoted = lines.subList(i, lines.size).joinToString("\n")
            return main to quoted
        }
    }
    return body to ""
}

/** Confirmation dialog shown before acting on a detected unsubscribe option. */
@Composable
private fun UnsubscribeDialog(
    method: UnsubscribeMethod,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val (message, confirmLabel) = when (method) {
        is UnsubscribeMethod.OneClickPost ->
            "Send an unsubscribe request to the sender?" to "Unsubscribe"
        is UnsubscribeMethod.OpenWeb ->
            "Open the unsubscribe page in your browser?" to "Open"
        is UnsubscribeMethod.SendMail ->
            "Compose an unsubscribe email to ${method.address}?" to "Compose"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unsubscribe)) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } },
    )
}

/** Act on a confirmed unsubscribe option. */
private fun performUnsubscribe(
    method: UnsubscribeMethod,
    context: android.content.Context,
    actions: MessageThreadActions,
    onCompose: (String, String) -> Unit,
) {
    when (method) {
        is UnsubscribeMethod.OneClickPost -> {
            AppMessages.show(context.getString(R.string.unsubscribing))
            actions.oneClickUnsubscribe(method.url) { ok ->
                val text = if (ok) "Unsubscribed" else "Unsubscribe failed"
                AppMessages.show(text)
            }
        }
        is UnsubscribeMethod.OpenWeb -> {
            val opened = runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, method.url.toUri()))
            }.isSuccess
            if (!opened) {
                AppMessages.show(context.getString(R.string.couldn_t_open_unsubscribe_page))
            }
        }
        is UnsubscribeMethod.SendMail -> onCompose(method.address, "Unsubscribe")
    }
}
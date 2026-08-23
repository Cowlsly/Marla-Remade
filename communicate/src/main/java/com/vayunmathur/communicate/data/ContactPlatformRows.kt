package com.vayunmathur.communicate.data

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log

/**
 * Publishes "reachable on WhatsApp / reachable on Signal" into the system contacts provider.
 *
 * Deliberately uses the **vendors' own mimetypes** rather than app-specific ones. The contacts app matches
 * platform rows by mimetype substring (`%whatsapp%`, `%securesms%`) and opens them with `ACTION_VIEW` on the
 * row's content URI, so writing under these mimetypes makes our rows indistinguishable from the real apps'
 * to any reader — which is the point: the existing contacts UI gains the actions with no changes, and it
 * works the same way whether the vendor app is installed or we are standing in for it.
 *
 * Rows live under our own account so they never collide with, or get overwritten by, the real vendor's sync.
 */
object ContactPlatformRows {
    private const val TAG = "ContactPlatformRows"

    /** The contacts app's own authenticator type, so these rows sit alongside its data. */
    private const val ACCOUNT_TYPE = "com.vayunmathur.contacts"
    private const val ACCOUNT_NAME = "Contacts"

    /** Exactly the strings WhatsApp and Signal use, so existing readers match them unchanged. */
    const val MIME_WHATSAPP_MESSAGE = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
    const val MIME_WHATSAPP_CALL = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
    const val MIME_WHATSAPP_VIDEO = "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
    const val MIME_SIGNAL_MESSAGE = "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.contact"
    const val MIME_SIGNAL_CALL = "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.call"
    const val MIME_SIGNAL_VIDEO = "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.video.call"

    private val WHATSAPP_MIMES = listOf(MIME_WHATSAPP_MESSAGE, MIME_WHATSAPP_CALL, MIME_WHATSAPP_VIDEO)
    private val SIGNAL_MIMES = listOf(MIME_SIGNAL_MESSAGE, MIME_SIGNAL_CALL, MIME_SIGNAL_VIDEO)

    /** One contact's reachability, as we know it. */
    data class Reachability(val e164: String, val whatsApp: Boolean, val signal: Boolean)

    /**
     * Publish [reachable] for the contacts they match, replacing whatever we published before.
     *
     * Idempotent: our previous rows are removed first, so a contact that has left a network loses the action
     * rather than keeping a stale one.
     */
    fun publish(context: Context, reachable: List<Reachability>) {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "no WRITE_CONTACTS; not publishing platform rows")
            return
        }
        if (!ensureAccount(context)) return

        val ops = ArrayList<ContentProviderOperation>()
        // Clear ours first; the account filter keeps this from touching the real vendors' rows.
        ops.add(
            ContentProviderOperation.newDelete(syncUri(ContactsContract.Data.CONTENT_URI))
                .withSelection(
                    "${ContactsContract.Data.MIMETYPE} IN (${(WHATSAPP_MIMES + SIGNAL_MIMES).joinToString(",") { "?" }})",
                    (WHATSAPP_MIMES + SIGNAL_MIMES).toTypedArray(),
                )
                .build(),
        )

        var rows = 0
        for (entry in reachable) {
            if (!entry.whatsApp && !entry.signal) continue
            val rawContactId = ourRawContactFor(context, entry.e164) ?: continue
            if (entry.whatsApp) {
                WHATSAPP_MIMES.forEach { mime -> ops.add(insertRow(rawContactId, mime, entry.e164, "WhatsApp")) }
                rows += WHATSAPP_MIMES.size
            }
            if (entry.signal) {
                SIGNAL_MIMES.forEach { mime -> ops.add(insertRow(rawContactId, mime, entry.e164, "Signal")) }
                rows += SIGNAL_MIMES.size
            }
        }

        try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            Log.i(TAG, "published $rows platform rows for ${reachable.size} contacts")
        } catch (t: Throwable) {
            Log.w(TAG, "could not publish platform rows", t)
        }
    }

    private fun insertRow(
        rawContactId: Long,
        mimeType: String,
        e164: String,
        label: String,
    ): ContentProviderOperation =
        ContentProviderOperation.newInsert(syncUri(ContactsContract.Data.CONTENT_URI))
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            .withValue(ContactsContract.Data.MIMETYPE, mimeType)
            // DATA1 is the identifier the row acts on; DATA2/DATA3 are what a contacts UI shows.
            .withValue(ContactsContract.Data.DATA1, e164)
            .withValue(ContactsContract.Data.DATA2, label)
            .withValue(ContactsContract.Data.DATA3, e164)
            .build()

    /**
     * Our raw contact for [e164], created if absent and linked to the aggregate contact by the phone number.
     *
     * A separate raw contact under our account is how the provider is meant to be extended: the aggregator
     * joins it to the user's existing contact by number, so the rows appear on the right person without us
     * editing anybody else's data.
     */
    private fun ourRawContactFor(context: Context, e164: String): Long? {
        existingRawContact(context, e164)?.let { return it }
        return try {
            val values = android.content.ContentValues().apply {
                put(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
                put(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
                put(ContactsContract.RawContacts.SYNC1, e164)
            }
            val uri = context.contentResolver.insert(syncUri(ContactsContract.RawContacts.CONTENT_URI), values)
                ?: return null
            val id = ContentUris.parseId(uri)
            // A phone row is what lets the aggregator match this to the user's existing contact.
            context.contentResolver.insert(
                syncUri(ContactsContract.Data.CONTENT_URI),
                android.content.ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, id)
                    put(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    )
                    put(ContactsContract.CommonDataKinds.Phone.NUMBER, e164)
                },
            )
            id
        } catch (t: Throwable) {
            Log.w(TAG, "could not create a raw contact for $e164", t)
            null
        }
    }

    private fun existingRawContact(context: Context, e164: String): Long? = try {
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${ContactsContract.RawContacts.SYNC1} = ?",
            arrayOf(ACCOUNT_TYPE, e164),
            null,
        )?.use { cursor: Cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    } catch (t: Throwable) {
        Log.w(TAG, "could not look up our raw contact for $e164", t)
        null
    }

    /**
     * Marks writes as coming from the sync adapter that owns the account. Without this the provider treats a
     * delete as a tombstone rather than removing the row.
     */
    private fun syncUri(uri: android.net.Uri) = uri.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
        .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, ACCOUNT_NAME)
        .build()

    /** The account must exist before the provider will accept rows attributed to it. */
    private fun ensureAccount(context: Context): Boolean = try {
        val manager = AccountManager.get(context)
        val account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)
        val present = manager.getAccountsByType(ACCOUNT_TYPE).any { it.name == ACCOUNT_NAME }
        if (!present) manager.addAccountExplicitly(account, null, null)
        true
    } catch (t: Throwable) {
        // Adding an account of another app's type needs a matching signature; log rather than fail the sync.
        Log.i(TAG, "could not ensure the contacts account exists: ${t.message}")
        false
    }
}

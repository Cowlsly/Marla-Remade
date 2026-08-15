package com.vayunmathur.everysync.sink

import android.content.ContentProviderOperation
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.util.Log
import androidx.core.database.getStringOrNull
import com.vayunmathur.everysync.auth.AccountStore
import com.vayunmathur.everysync.model.RemoteContact
import com.vayunmathur.everysync.model.TypedValue

/** A locally-edited contact detected via the platform DIRTY/DELETED flags. */
data class LocalContactChange(
    val rawContactId: Long,
    val sourceId: String?,
    val etag: String?,
    val deleted: Boolean,
    val contact: RemoteContact?,
)

/**
 * Writes [RemoteContact]s into ContactsContract under the EverySync account using
 * sync-adapter URIs (so our writes don't get flagged DIRTY). The remote UID is
 * stored in RawContacts.SOURCE_ID, the ETag in SYNC1 and the server href in SYNC2.
 */
object ContactsSink {
    private const val TAG = "ContactsSink"
    private val ACCOUNT_TYPE = AccountStore.ACCOUNT_TYPE

    private fun Uri.asSyncAdapter(accountName: String): Uri =
        buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
            .appendQueryParameter(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
            .build()

    /** Map of remote UID -> ETag currently present locally for this account. */
    fun localUidToEtag(context: Context, accountName: String): Map<String, String?> {
        val result = mutableMapOf<String, String?>()
        try {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.SOURCE_ID, ContactsContract.RawContacts.SYNC1),
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
                arrayOf(accountName, ACCOUNT_TYPE),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val uid = c.getStringOrNull(0) ?: continue
                    result[uid] = c.getStringOrNull(1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "localUidToEtag failed", e)
        }
        return result
    }

    private fun rawContactId(context: Context, accountName: String, uid: String): Long? {
        return try {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND ${ContactsContract.RawContacts.SOURCE_ID} = ?",
                arrayOf(accountName, ACCOUNT_TYPE, uid),
                null,
            )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        } catch (e: Exception) {
            Log.e(TAG, "rawContactId failed", e)
            null
        }
    }

    /** Idempotent upsert keyed on [RemoteContact.uid]. */
    fun upsert(context: Context, accountName: String, contact: RemoteContact) {
        val existing = rawContactId(context, accountName, contact.uid)
        val ops = ArrayList<ContentProviderOperation>()
        val rawUri = ContactsContract.RawContacts.CONTENT_URI.asSyncAdapter(accountName)
        val dataUri = ContactsContract.Data.CONTENT_URI.asSyncAdapter(accountName)

        if (existing != null) {
            // Replace: clear existing data rows, keep the raw contact + its id.
            ops += ContentProviderOperation.newUpdate(rawUri)
                .withSelection("${ContactsContract.RawContacts._ID} = ?", arrayOf(existing.toString()))
                .withValue(ContactsContract.RawContacts.SYNC1, contact.etag)
                .withValue(ContactsContract.RawContacts.SYNC2, contact.href)
                .withValue(ContactsContract.RawContacts.DIRTY, 0)
                .build()
            ops += ContentProviderOperation.newDelete(dataUri)
                .withSelection("${ContactsContract.Data.RAW_CONTACT_ID} = ?", arrayOf(existing.toString()))
                .build()
            dataOps(contact).forEach { builder ->
                ops += builder.withValue(ContactsContract.Data.RAW_CONTACT_ID, existing).build()
            }
        } else {
            ops += ContentProviderOperation.newInsert(rawUri)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, accountName)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE)
                .withValue(ContactsContract.RawContacts.SOURCE_ID, contact.uid)
                .withValue(ContactsContract.RawContacts.SYNC1, contact.etag)
                .withValue(ContactsContract.RawContacts.SYNC2, contact.href)
                .build()
            dataOps(contact).forEach { builder ->
                ops += builder.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).build()
            }
        }
        applyBatch(context, ops)
    }

    fun delete(context: Context, accountName: String, uid: String) {
        val id = rawContactId(context, accountName, uid) ?: return
        try {
            context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI.asSyncAdapter(accountName),
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(id.toString()),
            )
        } catch (e: Exception) {
            Log.e(TAG, "delete failed", e)
        }
    }

    /**
     * Removes every raw contact for this account from the on-device provider.
     * Used when the user turns contact sync off; the account itself stays, so a
     * later re-enable can repopulate it from a fresh pull. Deleting via the
     * sync-adapter URI hard-removes the rows rather than marking them DELETED.
     */
    fun purge(context: Context, accountName: String) {
        try {
            context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI.asSyncAdapter(accountName),
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
                arrayOf(accountName, ACCOUNT_TYPE),
            )
        } catch (e: Exception) {
            Log.e(TAG, "purge failed", e)
        }
    }

    /** Contacts the user edited/deleted locally (DIRTY/DELETED set by other apps). */
    fun getLocalChanges(context: Context, accountName: String): List<LocalContactChange> {
        val changes = mutableListOf<LocalContactChange>()
        try {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.RawContacts._ID,
                    ContactsContract.RawContacts.SOURCE_ID,
                    ContactsContract.RawContacts.SYNC1,
                    ContactsContract.RawContacts.DELETED,
                ),
                "${ContactsContract.RawContacts.ACCOUNT_NAME} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND (${ContactsContract.RawContacts.DIRTY} = 1 OR ${ContactsContract.RawContacts.DELETED} = 1)",
                arrayOf(accountName, ACCOUNT_TYPE),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val rawId = c.getLong(0)
                    val deleted = c.getInt(3) == 1
                    changes += LocalContactChange(
                        rawContactId = rawId,
                        sourceId = c.getStringOrNull(1),
                        etag = c.getStringOrNull(2),
                        deleted = deleted,
                        contact = if (deleted) null else readContact(context, rawId, c.getStringOrNull(1)),
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getLocalChanges failed", e)
        }
        return changes
    }

    fun clearDirty(context: Context, accountName: String, rawContactId: Long) {
        try {
            context.contentResolver.update(
                ContactsContract.RawContacts.CONTENT_URI.asSyncAdapter(accountName),
                android.content.ContentValues().apply { put(ContactsContract.RawContacts.DIRTY, 0) },
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(rawContactId.toString()),
            )
        } catch (e: Exception) {
            Log.e(TAG, "clearDirty failed", e)
        }
    }

    /** Attach a remote UID/ETag to a locally-created raw contact after pushing it. */
    fun setSourceId(context: Context, accountName: String, rawContactId: Long, uid: String, etag: String?) {
        try {
            context.contentResolver.update(
                ContactsContract.RawContacts.CONTENT_URI.asSyncAdapter(accountName),
                android.content.ContentValues().apply {
                    put(ContactsContract.RawContacts.SOURCE_ID, uid)
                    put(ContactsContract.RawContacts.SYNC1, etag)
                    put(ContactsContract.RawContacts.DIRTY, 0)
                },
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(rawContactId.toString()),
            )
        } catch (e: Exception) {
            Log.e(TAG, "setSourceId failed", e)
        }
    }

    private fun readContact(context: Context, rawId: Long, uid: String?): RemoteContact {
        var display = ""
        var prefix = ""; var first = ""; var middle = ""; var last = ""; var suffix = ""
        var org = ""; var note = ""; var bday: String? = null
        val phones = mutableListOf<TypedValue>()
        val emails = mutableListOf<TypedValue>()
        val addresses = mutableListOf<TypedValue>()
        try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.Data.DATA1,
                    ContactsContract.Data.DATA2,
                    ContactsContract.Data.DATA3,
                    ContactsContract.Data.DATA4,
                    ContactsContract.Data.DATA5,
                    ContactsContract.Data.DATA6,
                ),
                "${ContactsContract.Data.RAW_CONTACT_ID} = ?",
                arrayOf(rawId.toString()),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    when (c.getStringOrNull(0)) {
                        StructuredName.CONTENT_ITEM_TYPE -> {
                            display = c.getStringOrNull(1) ?: ""
                            first = c.getStringOrNull(2) ?: ""
                            last = c.getStringOrNull(3) ?: ""
                            prefix = c.getStringOrNull(4) ?: ""
                            middle = c.getStringOrNull(5) ?: ""
                            suffix = c.getStringOrNull(6) ?: ""
                        }
                        Phone.CONTENT_ITEM_TYPE -> phones += TypedValue(c.getStringOrNull(1) ?: "", c.getInt(2))
                        Email.CONTENT_ITEM_TYPE -> emails += TypedValue(c.getStringOrNull(1) ?: "", c.getInt(2))
                        StructuredPostal.CONTENT_ITEM_TYPE -> addresses += TypedValue(c.getStringOrNull(1) ?: "", c.getInt(2))
                        Organization.CONTENT_ITEM_TYPE -> org = c.getStringOrNull(1) ?: ""
                        Note.CONTENT_ITEM_TYPE -> note = c.getStringOrNull(1) ?: ""
                        Event.CONTENT_ITEM_TYPE -> if (c.getInt(2) == Event.TYPE_BIRTHDAY) bday = c.getStringOrNull(1)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "readContact failed", e)
        }
        return RemoteContact(
            uid = uid ?: "",
            displayName = display,
            prefix = prefix, firstName = first, middleName = middle, lastName = last, suffix = suffix,
            organization = org, note = note, phones = phones, emails = emails, addresses = addresses,
            birthday = bday,
        )
    }

    private fun dataOps(c: RemoteContact): List<ContentProviderOperation.Builder> {
        val ops = mutableListOf<ContentProviderOperation.Builder>()
        ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
            .withValue(StructuredName.DISPLAY_NAME, c.displayName)
            .withValue(StructuredName.GIVEN_NAME, c.firstName)
            .withValue(StructuredName.FAMILY_NAME, c.lastName)
            .withValue(StructuredName.MIDDLE_NAME, c.middleName)
            .withValue(StructuredName.PREFIX, c.prefix)
            .withValue(StructuredName.SUFFIX, c.suffix)
        if (c.organization.isNotBlank()) {
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE)
                .withValue(Organization.COMPANY, c.organization)
        }
        for (p in c.phones) ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
            .withValue(Phone.NUMBER, p.value).withValue(Phone.TYPE, p.type)
        for (e in c.emails) ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
            .withValue(Email.ADDRESS, e.value).withValue(Email.TYPE, e.type)
        for (a in c.addresses) ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
            .withValue(StructuredPostal.FORMATTED_ADDRESS, a.value).withValue(StructuredPostal.TYPE, a.type)
        if (c.note.isNotBlank()) ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
            .withValue(Note.NOTE, c.note)
        c.birthday?.let { bday ->
            ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.MIMETYPE, Event.CONTENT_ITEM_TYPE)
                .withValue(Event.START_DATE, bday).withValue(Event.TYPE, Event.TYPE_BIRTHDAY)
        }
        return ops
    }

    private fun applyBatch(context: Context, ops: ArrayList<ContentProviderOperation>) {
        if (ops.isEmpty()) return
        try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        } catch (e: Exception) {
            Log.e(TAG, "applyBatch failed", e)
        }
    }
}

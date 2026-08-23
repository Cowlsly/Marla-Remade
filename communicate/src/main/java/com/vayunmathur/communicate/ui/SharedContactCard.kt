package com.vayunmathur.communicate.ui

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

/** A contact card as picked from the address book, ready to share. */
data class SharedContactCard(
    val givenName: String,
    val familyName: String?,
    /** Number paired with its label, so a "Work" number stays labelled on the other side. */
    val phoneNumbers: List<Pair<String, String?>>,
    val emails: List<String>,
)

/**
 * Read a contact the user picked, for sharing.
 *
 * `PickContact` returns a contact uri, not the data rows, so numbers and emails have to be queried separately.
 * Returns null when nothing usable could be read, so the caller reports a failure rather than sending an empty
 * card.
 */
fun readSharedContact(context: Context, uri: Uri): SharedContactCard? = try {
    val resolver = context.contentResolver
    val contactId: String
    var displayName = ""
    resolver.query(
        uri,
        arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return null
        contactId = cursor.getString(0)
        displayName = cursor.getString(1) ?: ""
    } ?: return null

    val id = resolver.query(
        uri,
        arrayOf(ContactsContract.Contacts._ID),
        null,
        null,
        null,
    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return null

    val numbers = mutableListOf<Pair<String, String?>>()
    resolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        ),
        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
        arrayOf(id),
        null,
    )?.use { cursor ->
        while (cursor.moveToNext()) {
            val number = cursor.getString(0)?.trim().orEmpty()
            if (number.isNotEmpty()) numbers += number to cursor.getString(1)
        }
    }

    val emails = mutableListOf<String>()
    resolver.query(
        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
        "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
        arrayOf(id),
        null,
    )?.use { cursor ->
        while (cursor.moveToNext()) {
            cursor.getString(0)?.trim()?.takeIf { it.isNotEmpty() }?.let { emails += it }
        }
    }

    if (displayName.isBlank() && numbers.isEmpty()) return null
    // The address book stores one display name; split it so the card carries a structured name.
    val parts = displayName.trim().split(' ', limit = 2)
    SharedContactCard(
        givenName = parts.firstOrNull().orEmpty().ifBlank { numbers.firstOrNull()?.first.orEmpty() },
        familyName = parts.getOrNull(1),
        phoneNumbers = numbers,
        emails = emails,
    )
} catch (t: Throwable) {
    Log.w("SharedContact", "could not read the picked contact", t)
    null
}

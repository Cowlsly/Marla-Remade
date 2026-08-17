package com.vayunmathur.maps.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconContacts
import com.vayunmathur.library.ui.rememberPermissionRequest

/**
 * Contact-address shortcut (P17): a button (sat next to the P8 mic) that lets the
 * user grab a postal address from their contacts and drop it into the search box.
 *
 * P25 rework — the old [Intent.ACTION_PICK] over
 * [ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI] crashed with
 * [ActivityNotFoundException] on modern Android (nothing registers to PICK that
 * bare data URI). We now try the pickers in order and NEVER let a missing handler
 * crash the app:
 *
 *  1. The postal-row picker — [Intent.ACTION_PICK] typed to
 *     [StructuredPostal.CONTENT_TYPE] (`vnd.android.cursor.dir/postal-address_v2`).
 *     The system contacts app returns the chosen StructuredPostal *data row* URI
 *     with an implicit per-row read grant, so we read it with no READ_CONTACTS.
 *  2. Fallback — the AndroidX [ActivityResultContracts.PickContact] contract
 *     (ACTION_PICK over the universally-handled Contacts URI), which returns a
 *     whole-contact URI. We resolve its first postal address, which needs
 *     READ_CONTACTS; we request it through the shared [rememberPermissionRequest]
 *     (deep-links to settings on permanent denial) and retry once granted.
 *
 * Every launch and every query is wrapped so a cancel, a contact with no postal
 * address, or an OEM with no picker at all is a quiet no-op (with a brief toast
 * only when literally nothing can handle the pick). On a hit it reads
 * [StructuredPostal.FORMATTED_ADDRESS] (falling back to composing
 * street/city/region/postcode/country) and hands the string to [onAddress];
 * callers fill the search query, run the P3 Google search and auto-select the
 * first hit. Works on Android 17 and older.
 */
@Composable
fun ContactAddressButton(onAddress: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // A whole-contact URI whose postal read was refused for lack of READ_CONTACTS;
    // retried once the permission is granted. Null while nothing is pending.
    var pendingContact by remember { mutableStateOf<Uri?>(null) }

    val requestContacts =
        rememberPermissionRequest(Manifest.permission.READ_CONTACTS) { granted ->
            val contact = pendingContact
            pendingContact = null
            if (granted && contact != null) {
                runCatching { readContactAddress(context, contact) }.getOrNull()?.let(onAddress)
            }
        }

    // Fallback picker: pick a whole contact, then resolve its postal address
    // (needs READ_CONTACTS). Cancel → null → no-op.
    val contactPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickContact(),
    ) { contact ->
        if (contact == null) return@rememberLauncherForActivityResult
        try {
            readContactAddress(context, contact)?.let(onAddress)
        } catch (_: SecurityException) {
            // No implicit grant on a whole-contact pick — get READ_CONTACTS and retry.
            pendingContact = contact
            requestContacts()
        }
    }

    // Primary picker: the system postal-address picker returns a specific
    // StructuredPostal data row with an implicit per-row read grant.
    val postalPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Cancel (or no data) → no-op.
        val row = result.data?.data ?: return@rememberLauncherForActivityResult
        try {
            val address = readAddress(context, row)
            if (address != null) {
                onAddress(address)
            } else {
                // Row carried no address (or wasn't a postal row) — fall back to
                // the whole-contact picker.
                launchContactPicker(context, contactPicker)
            }
        } catch (_: SecurityException) {
            // The implicit per-row grant didn't cover the query on this OEM —
            // fall back to the whole-contact picker + READ_CONTACTS path.
            launchContactPicker(context, contactPicker)
        }
    }

    IconButton(
        onClick = {
            val postalIntent = Intent(Intent.ACTION_PICK).apply {
                type = ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_TYPE
            }
            try {
                postalPicker.launch(postalIntent)
            } catch (_: ActivityNotFoundException) {
                // No postal picker on this device — try the universal contact picker.
                launchContactPicker(context, contactPicker)
            }
        },
        modifier = modifier,
    ) {
        IconContacts()
    }
}

/** Launch the whole-contact picker, degrading to a quiet toast if even that has
 *  no handler (some stripped OEM images). Never throws. */
private fun launchContactPicker(
    context: Context,
    contactPicker: androidx.activity.result.ActivityResultLauncher<Void?>,
) {
    try {
        contactPicker.launch(null)
    } catch (_: ActivityNotFoundException) {
        runCatching {
            Toast.makeText(context, context.getString(com.vayunmathur.maps.R.string.no_contact_picker), Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Read the postal address at [rowUri] (a StructuredPostal data row). Prefers the
 * pre-formatted [StructuredPostal.FORMATTED_ADDRESS]; if that's blank it
 * composes the address from the individual components. Returns null when the
 * row is gone or holds no address. May throw [SecurityException] when the
 * caller lacks read access to the row.
 */
private fun readAddress(context: Context, rowUri: Uri): String? {
    context.contentResolver.query(rowUri, POSTAL_PROJECTION, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return null
        return cursor.postalAddress()
    }
    return null
}

/**
 * Resolve the first postal address of the whole contact at [contactUri] (as
 * returned by [ActivityResultContracts.PickContact]). Looks up the contact id,
 * then reads its StructuredPostal data rows. Returns null when the contact has
 * no postal address. Requires READ_CONTACTS (may throw [SecurityException]).
 */
private fun readContactAddress(context: Context, contactUri: Uri): String? {
    val contactId = context.contentResolver.query(
        contactUri,
        arrayOf(ContactsContract.Contacts._ID),
        null,
        null,
        null,
    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return null

    val selection =
        "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
    val args = arrayOf(
        contactId,
        ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
    )
    context.contentResolver.query(
        ContactsContract.Data.CONTENT_URI,
        POSTAL_PROJECTION,
        selection,
        args,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return null
        return cursor.postalAddress()
    }
    return null
}

/** Pull a display address out of a StructuredPostal cursor row: the formatted
 *  address if present, else the composed components. Null when the row is empty. */
private fun Cursor.postalAddress(): String? {
    fun col(name: String): String? = getColumnIndex(name)
        .takeIf { it >= 0 }
        ?.let { getString(it) }
        ?.takeIf { it.isNotBlank() }

    col(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)
        ?.let { return it }

    return listOfNotNull(
        col(ContactsContract.CommonDataKinds.StructuredPostal.STREET),
        col(ContactsContract.CommonDataKinds.StructuredPostal.CITY),
        col(ContactsContract.CommonDataKinds.StructuredPostal.REGION),
        col(ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE),
        col(ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY),
    ).joinToString(", ").ifBlank { null }
}

private val POSTAL_PROJECTION = arrayOf(
    ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
    ContactsContract.CommonDataKinds.StructuredPostal.STREET,
    ContactsContract.CommonDataKinds.StructuredPostal.CITY,
    ContactsContract.CommonDataKinds.StructuredPostal.REGION,
    ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE,
    ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY,
)

package com.vayunmathur.maps.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsPickerSessionContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconContacts
import com.vayunmathur.library.ui.rememberPermissionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Contact-address shortcut (P17): a button (sat next to the P8 mic) that lets the
 * user grab a postal address from their contacts and drop it into the search box.
 *
 * P30 rework — prefer the new Android 17 (API 37, [Build.VERSION_CODES.CINNAMON_BUN])
 * system Contact Picker. We ask it for the *postal-address* data field only, so the
 * user picks a single ADDRESS rather than a whole contact. The picker hands back a
 * short-lived *session URI* over which we read exactly the chosen row(s) — no
 * READ_CONTACTS required. Older devices (and Android 17 images without the picker)
 * fall through to the previous behaviour, and nothing ever crashes:
 *
 *  0. NEW — [ContactsPickerSessionContract.ACTION_PICK_CONTACTS] requesting
 *     [StructuredPostal.CONTENT_ITEM_TYPE] via
 *     [ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS],
 *     single-select (we deliberately omit [Intent.EXTRA_ALLOW_MULTIPLE]) and
 *     [Intent.EXTRA_USE_SYSTEM_CONTACTS_PICKER] so it also engages when targeting a
 *     lower SDK on an Android 17 device. On RESULT_OK we read the returned session
 *     URI on [Dispatchers.IO] (the session URI does NOT accept selection args) and
 *     pull the one chosen address.
 *  1. Fallback — the postal-row picker: [Intent.ACTION_PICK] typed to
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
    val scope = rememberCoroutineScope()

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

    // Fallback #2: pick a whole contact, then resolve its postal address
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

    // Fallback #1: the system postal-address picker returns a specific
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

    // Primary (Android 17+): the new system Contact Picker returns a session URI
    // scoped to the user's chosen postal address. We read it off the main thread.
    val addressPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val sessionUri = result.data?.data ?: return@rememberLauncherForActivityResult
        scope.launch {
            val address = withContext(Dispatchers.IO) {
                runCatching { readSessionAddress(context, sessionUri) }.getOrNull()
            }
            if (address != null) onAddress(address)
        }
    }

    IconButton(
        onClick = {
            val launchedNewPicker =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                    try {
                        addressPicker.launch(newSystemContactPickerIntent())
                        true
                    } catch (_: ActivityNotFoundException) {
                        // Android 17 image without the system picker — use the old path.
                        false
                    }
                } else {
                    false
                }
            if (!launchedNewPicker) {
                launchPostalPicker(context, postalPicker, contactPicker)
            }
        },
        modifier = modifier,
    ) {
        IconContacts()
    }
}

/**
 * Build the Android 17 system Contact Picker intent: request the postal-address
 * field only (so the user picks an ADDRESS), single-select. All extras below are
 * compile-time String constants and are therefore inlined, so referencing the
 * new [ContactsPickerSessionContract] symbols is safe even on older runtimes;
 * the call is additionally gated on [Build.VERSION_CODES.CINNAMON_BUN].
 */
@RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
private fun newSystemContactPickerIntent(): Intent =
    Intent(ContactsPickerSessionContract.ACTION_PICK_CONTACTS).apply {
        // Also engage the system picker when targeting a lower SDK on Android 17.
        putExtra(Intent.EXTRA_USE_SYSTEM_CONTACTS_PICKER, true)
        // Ask only for postal-address rows.
        putStringArrayListExtra(
            ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS,
            arrayListOf(StructuredPostal.CONTENT_ITEM_TYPE),
        )
        // Single-select: intentionally do NOT set Intent.EXTRA_ALLOW_MULTIPLE.
    }

/** Legacy postal-picker path: try the postal-row picker, then the whole-contact
 *  picker. Never throws. */
private fun launchPostalPicker(
    context: Context,
    postalPicker: ActivityResultLauncher<Intent>,
    contactPicker: ActivityResultLauncher<Void?>,
) {
    val postalIntent = Intent(Intent.ACTION_PICK).apply {
        type = StructuredPostal.CONTENT_TYPE
    }
    try {
        postalPicker.launch(postalIntent)
    } catch (_: ActivityNotFoundException) {
        // No postal picker on this device — try the universal contact picker.
        launchContactPicker(context, contactPicker)
    }
}

/** Launch the whole-contact picker, degrading to a quiet toast if even that has
 *  no handler (some stripped OEM images). Never throws. */
private fun launchContactPicker(
    context: Context,
    contactPicker: ActivityResultLauncher<Void?>,
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
 * Read the address from the Android 17 picker's [sessionUri]. The session URI is
 * a pre-scoped view of exactly what the user chose, so it does NOT support
 * selection / selectionArgs (passing them throws) — query with nulls. We iterate
 * the returned rows and take the first StructuredPostal address. Because we
 * requested only StructuredPostal and single-select, that is the user's pick.
 * Returns null when nothing usable is present.
 */
private fun readSessionAddress(context: Context, sessionUri: Uri): String? {
    context.contentResolver.query(sessionUri, SESSION_PROJECTION, null, null, null)?.use { cursor ->
        val mimeIdx = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
        while (cursor.moveToNext()) {
            val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
            if (mime == null || mime == StructuredPostal.CONTENT_ITEM_TYPE) {
                cursor.postalAddress()?.let { return it }
            }
        }
    }
    return null
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
        StructuredPostal.CONTENT_ITEM_TYPE,
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

    col(StructuredPostal.FORMATTED_ADDRESS)
        ?.let { return it }

    return listOfNotNull(
        col(StructuredPostal.STREET),
        col(StructuredPostal.CITY),
        col(StructuredPostal.REGION),
        col(StructuredPostal.POSTCODE),
        col(StructuredPostal.COUNTRY),
    ).joinToString(", ").ifBlank { null }
}

/** Projection for the Android 17 session URI: MIMETYPE (to spot the postal row)
 *  plus the postal columns. */
private val SESSION_PROJECTION = arrayOf(
    ContactsContract.Data.MIMETYPE,
    StructuredPostal.FORMATTED_ADDRESS,
    StructuredPostal.STREET,
    StructuredPostal.CITY,
    StructuredPostal.REGION,
    StructuredPostal.POSTCODE,
    StructuredPostal.COUNTRY,
)

private val POSTAL_PROJECTION = arrayOf(
    StructuredPostal.FORMATTED_ADDRESS,
    StructuredPostal.STREET,
    StructuredPostal.CITY,
    StructuredPostal.REGION,
    StructuredPostal.POSTCODE,
    StructuredPostal.COUNTRY,
)

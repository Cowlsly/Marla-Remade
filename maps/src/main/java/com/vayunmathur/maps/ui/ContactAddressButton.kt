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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconContacts
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.library.ui.R as UiR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Contact-address shortcut (P17): a button (sat next to the P8 mic) that lets the
 * user grab a postal address from their contacts and drop it into the search box.
 *
 * P31 rework — pick a specific ADDRESS, never a whole contact, and never silently
 * collapse a contact's several addresses to the first.
 *
 * On Android 17 (API 37, [Build.VERSION_CODES.CINNAMON_BUN]) we use the new system
 * Contact Picker ([ContactsPickerSessionContract.ACTION_PICK_CONTACTS]). The full
 * contract exposes only these config extras — there is NO dedicated "show data
 * items instead of contacts" mode flag:
 *   • [ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS] —
 *     the mimetypes to surface; we pass only [StructuredPostal.CONTENT_ITEM_TYPE]
 *     so the picker filters to (and displays) postal-address data.
 *   • [ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_SELECTION_LIMIT] — the
 *     max number of items selectable; we set 1 to request native single-item
 *     selection of one address.
 *   • [ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_MATCH_ALL_DATA_FIELDS] —
 *     ANY-vs-ALL field matching (irrelevant with a single requested field).
 *   • [Intent.EXTRA_USE_SYSTEM_CONTACTS_PICKER] — force the system picker even when
 *     targeting a lower SDK on an Android 17 device.
 * We deliberately omit [Intent.EXTRA_ALLOW_MULTIPLE] (single-select).
 *
 * The picker returns a short-lived *session URI* over which we read exactly the
 * chosen row(s) — no READ_CONTACTS. Because the contract has no guaranteed
 * pre-selected-single-item guarantee across OEMs, we read *all* StructuredPostal
 * addresses in the session: exactly one → use it; several → present an in-app
 * chooser ([AddressChooserDialog]) so the user picks the specific address rather
 * than us taking the first.
 *
 * Older devices (and Android 17 images without the picker) fall through, and
 * nothing ever crashes:
 *  1. Fallback — the postal-row picker: [Intent.ACTION_PICK] typed to
 *     [StructuredPostal.CONTENT_TYPE] (`vnd.android.cursor.dir/postal-address_v2`).
 *     The system contacts app returns the chosen StructuredPostal *data row* URI
 *     with an implicit per-row read grant, so we read it with no READ_CONTACTS.
 *  2. Fallback — the AndroidX [ActivityResultContracts.PickContact] contract,
 *     which returns a whole-contact URI. We resolve its postal addresses (needs
 *     READ_CONTACTS, requested via the shared [rememberPermissionRequest]); if the
 *     contact has several, the same in-app chooser is shown.
 *
 * A cancel, a contact with no postal address, or an OEM with no picker at all is a
 * quiet no-op (with a brief toast only when literally nothing can handle the pick).
 * Each hit reads [StructuredPostal.FORMATTED_ADDRESS] (falling back to composing
 * street/city/region/postcode/country) and hands the string to [onAddress].
 */
@Composable
fun ContactAddressButton(onAddress: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // A whole-contact URI whose postal read was refused for lack of READ_CONTACTS;
    // retried once the permission is granted. Null while nothing is pending.
    var pendingContact by remember { mutableStateOf<Uri?>(null) }

    // When a pick yields several addresses we can't disambiguate, we show a chooser.
    var addressChoices by remember { mutableStateOf<List<String>>(emptyList()) }

    // Deliver the resolved address(es): none → no-op; one → use it; several → let
    // the user pick the specific one (never collapse to the first).
    fun deliver(addresses: List<String>) {
        when {
            addresses.isEmpty() -> Unit
            addresses.size == 1 -> onAddress(addresses.first())
            else -> addressChoices = addresses
        }
    }

    val requestContacts =
        rememberPermissionRequest(Manifest.permission.READ_CONTACTS) { granted ->
            val contact = pendingContact
            pendingContact = null
            if (granted && contact != null) {
                deliver(runCatching { readContactAddresses(context, contact) }.getOrDefault(emptyList()))
            }
        }

    // Fallback #2: pick a whole contact, then resolve its postal address(es)
    // (needs READ_CONTACTS). Cancel → null → no-op.
    val contactPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickContact(),
    ) { contact ->
        if (contact == null) return@rememberLauncherForActivityResult
        try {
            deliver(readContactAddresses(context, contact))
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
    // scoped to the user's chosen postal address(es). We read it off the main thread.
    val addressPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val sessionUri = result.data?.data ?: return@rememberLauncherForActivityResult
        scope.launch {
            val addresses = withContext(Dispatchers.IO) {
                runCatching { readSessionAddresses(context, sessionUri) }.getOrDefault(emptyList())
            }
            deliver(addresses)
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

    if (addressChoices.isNotEmpty()) {
        AddressChooserDialog(
            addresses = addressChoices,
            onPick = { picked ->
                addressChoices = emptyList()
                onAddress(picked)
            },
            onDismiss = { addressChoices = emptyList() },
        )
    }
}

/** In-app chooser for when a pick resolves to several postal addresses: lists each
 *  [FORMATTED_ADDRESS] (or composed) string; tapping one selects it. */
@Composable
private fun AddressChooserDialog(
    addresses: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(com.vayunmathur.maps.R.string.choose_address)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                items(addresses) { address ->
                    ListItem(
                        content = { Text(address) },
                        modifier = Modifier.clickable { onPick(address) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        },
    )
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
        // Ask only for postal-address rows → picker surfaces addresses, not names.
        putStringArrayListExtra(
            ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS,
            arrayListOf(StructuredPostal.CONTENT_ITEM_TYPE),
        )
        // Native single-item selection: the user picks ONE address.
        putExtra(ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_SELECTION_LIMIT, 1)
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
 * Read every postal address from the Android 17 picker's [sessionUri]. The session
 * URI is a pre-scoped view of exactly what the user chose, so it does NOT support
 * selection / selectionArgs (passing them throws) — query with nulls. We iterate
 * the returned rows and collect each StructuredPostal address (de-duplicated,
 * order preserved). With SELECTION_LIMIT=1 this is normally one address; if an OEM
 * returns the whole chosen contact's several addresses, the caller shows a chooser.
 */
private fun readSessionAddresses(context: Context, sessionUri: Uri): List<String> {
    val out = LinkedHashSet<String>()
    context.contentResolver.query(sessionUri, SESSION_PROJECTION, null, null, null)?.use { cursor ->
        val mimeIdx = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
        while (cursor.moveToNext()) {
            val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
            if (mime == null || mime == StructuredPostal.CONTENT_ITEM_TYPE) {
                cursor.postalAddress()?.let { out.add(it) }
            }
        }
    }
    return out.toList()
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
 * Resolve all postal addresses of the whole contact at [contactUri] (as returned
 * by [ActivityResultContracts.PickContact]). Looks up the contact id, then reads
 * its StructuredPostal data rows (de-duplicated, order preserved). Returns empty
 * when the contact has no postal address. Requires READ_CONTACTS (may throw
 * [SecurityException]).
 */
private fun readContactAddresses(context: Context, contactUri: Uri): List<String> {
    val contactId = context.contentResolver.query(
        contactUri,
        arrayOf(ContactsContract.Contacts._ID),
        null,
        null,
        null,
    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return emptyList()

    val selection =
        "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
    val args = arrayOf(
        contactId,
        StructuredPostal.CONTENT_ITEM_TYPE,
    )
    val out = LinkedHashSet<String>()
    context.contentResolver.query(
        ContactsContract.Data.CONTENT_URI,
        POSTAL_PROJECTION,
        selection,
        args,
        null,
    )?.use { cursor ->
        while (cursor.moveToNext()) {
            cursor.postalAddress()?.let { out.add(it) }
        }
    }
    return out.toList()
}

/** Pull a display address out of a StructuredPostal cursor row: the formatted
 *  address if present, else the composed components. Null when the row is empty. */
private fun Cursor.postalAddress(): String? {
    fun col(name: String): String? = getColumnIndex(name)
        .takeIf { it >= 0 }
        ?.let { getString(it) }
        ?.takeIf { it.isNotBlank() }

    col(StructuredPostal.FORMATTED_ADDRESS)
        ?.normalizeAddress()
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }

    return listOfNotNull(
        col(StructuredPostal.STREET),
        col(StructuredPostal.CITY),
        col(StructuredPostal.REGION),
        col(StructuredPostal.POSTCODE),
        col(StructuredPostal.COUNTRY),
    ).joinToString(", ").ifBlank { null }
}

/** Collapse a multi-line postal address to one comma-separated line so it makes
 *  a valid single-line search query (FORMATTED_ADDRESS embeds newlines). */
private fun String.normalizeAddress(): String =
    lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")

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

package com.vayunmathur.maps.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
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
 * Contact-address shortcut (P17): a button (sat next to the P8 mic) that opens
 * the SYSTEM postal-address picker — [Intent.ACTION_PICK] over
 * [ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI] — routed
 * through [ActivityResultContracts.StartActivityForResult]. The OS surfaces
 * every contacts provider generically, so this works whether or not the MA
 * contacts app is installed.
 *
 * On a pick it reads the chosen row's [StructuredPostal.FORMATTED_ADDRESS]
 * (falling back to composing street/city/region/postcode/country) and hands the
 * address string to [onAddress]; callers fill the search query, run the P3
 * Google search and auto-select the first hit.
 *
 * The picker grants an implicit per-row read on the returned URI, so no
 * READ_CONTACTS is needed in the common path. If an OEM refuses that read we
 * degrade gracefully: request READ_CONTACTS through the shared
 * [rememberPermissionRequest] (which deep-links to app settings on permanent
 * denial) and retry the same row once granted. Cancel / a row with no address
 * is a no-op.
 */
@Composable
fun ContactAddressButton(onAddress: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Row whose read was refused without the implicit grant; retried once
    // READ_CONTACTS is granted. Null while nothing is pending.
    var pendingRow by remember { mutableStateOf<Uri?>(null) }

    val requestContacts =
        rememberPermissionRequest(Manifest.permission.READ_CONTACTS) { granted ->
            val row = pendingRow
            pendingRow = null
            if (granted && row != null) {
                runCatching { readAddress(context, row) }.getOrNull()?.let(onAddress)
            }
        }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Cancel (or no data) → no-op.
        val row = result.data?.data ?: return@rememberLauncherForActivityResult
        try {
            readAddress(context, row)?.let(onAddress)
        } catch (_: SecurityException) {
            // The implicit per-row grant didn't cover the query on this OEM;
            // fall back to the runtime READ_CONTACTS permission and retry.
            pendingRow = row
            requestContacts()
        }
    }

    IconButton(
        onClick = {
            picker.launch(
                Intent(
                    Intent.ACTION_PICK,
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
                )
            )
        },
        modifier = modifier,
    ) {
        IconContacts()
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
    val projection = arrayOf(
        ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
        ContactsContract.CommonDataKinds.StructuredPostal.STREET,
        ContactsContract.CommonDataKinds.StructuredPostal.CITY,
        ContactsContract.CommonDataKinds.StructuredPostal.REGION,
        ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE,
        ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY,
    )
    context.contentResolver.query(rowUri, projection, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return null

        fun col(name: String): String? = cursor.getColumnIndex(name)
            .takeIf { it >= 0 }
            ?.let { cursor.getString(it) }
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
    return null
}

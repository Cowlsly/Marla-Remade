package com.vayunmathur.communicate.ui

import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import com.vayunmathur.library.ui.ExternalIntents

/**
 * Hand a phone number to the system contacts editor.
 *
 * Both use the platform's standard intents rather than talking to the provider directly, so whichever
 * contacts app the user prefers handles it and the usual account/duplicate handling applies.
 */
object ContactIntents {
    /** Open a new-contact editor pre-filled with [number]. */
    fun createNew(context: Context, number: String) {
        if (number.isBlank()) return
        ExternalIntents.launch(
            context,
            Intent(ContactsContract.Intents.Insert.ACTION).apply {
                type = ContactsContract.RawContacts.CONTENT_TYPE
                putExtra(ContactsContract.Intents.Insert.PHONE, number)
            },
        )
    }

    /**
     * Open the contact picker so [number] can be attached to a contact that already exists.
     *
     * `ACTION_INSERT_OR_EDIT` is the documented way to get the "add to existing contact" chooser; a plain
     * insert would always create a new entry.
     */
    fun addToExisting(context: Context, number: String) {
        if (number.isBlank()) return
        ExternalIntents.launch(
            context,
            Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
                type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
                putExtra(ContactsContract.Intents.Insert.PHONE, number)
            },
        )
    }
}

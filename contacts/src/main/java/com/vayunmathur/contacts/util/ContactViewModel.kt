@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class, kotlinx.coroutines.FlowPreview::class)

package com.vayunmathur.contacts.util
import android.app.Application
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.ContactsContract
import android.util.Log
import androidx.collection.LruCache
import androidx.core.graphics.scale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.contacts.data.Address
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKStructuredPostal
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.ContactGroup
import com.vayunmathur.contacts.data.ContactPrefill
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.contacts.data.GroupMembership
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.Photo
import com.vayunmathur.contacts.data.PrefillValue
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.LOCAL_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.SimContact
import com.vayunmathur.contacts.data.SimContactsDataSource
import com.vayunmathur.contacts.data.isSimAccountType
import com.vayunmathur.contacts.data.isDefaultLocalAccount
import com.vayunmathur.contacts.data.isLocalAccountType
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vayunmathur.contacts.util.ContactSorting.sortedByNameLocale
import kotlinx.datetime.LocalDate
import kotlin.io.encoding.Base64

data class ContactAccount(val name: String, val type: String)

data class ContactGroupMembership(val contactId: Long, val groupId: Long)

/**
 * Implements [ContactsActions] so a binder can hand itself straight to a stateless screen;
 * the navigating members keep their no-op defaults and are overridden per screen, since the
 * ViewModel has no back stack.
 */
class ContactViewModel(application: Application) : AndroidViewModel(application), ContactsActions {

    private val dataStore = DataStoreUtils.getInstance(application)

    // Provider-backed in-memory contact list (no local DB). Populated by
    // syncFromSystem() from the system Contacts provider + SIM ADN and refreshed via the
    // ContentObserver registered in init.
    private val _allContacts = MutableStateFlow<List<Contact>>(emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    val hiddenAccounts: StateFlow<Set<String>> = dataStore.stringSetFlow("hidden_accounts")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private fun accountKey(type: String?, name: String?): String = "${type ?: ""}|${name ?: ""}"

    val contacts: StateFlow<List<com.vayunmathur.contacts.data.Contact>> = combine(
        _allContacts,
        _searchQuery,
        hiddenAccounts
    ) { all, query, hidden ->
        val filtered = all.filter { c ->
            val key = accountKey(c.accountType, c.accountName)
            // Support legacy hidden entries that stored only accountName
            key !in hidden && c.accountName !in hidden
        }
        filterBySearch(filtered, query)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Virtual SIM account display labels: key is "type|name" -> "SIM N — Carrier"
    private val _simAccountLabels = MutableStateFlow<Map<String, String>>(emptyMap())
    val simAccountLabels: StateFlow<Map<String, String>> = _simAccountLabels.asStateFlow()

    fun simDisplayLabel(account: ContactAccount): String? = _simAccountLabels.value["${account.type}|${account.name}"]
    fun simDisplayLabelFor(type: String?, name: String?): String? = _simAccountLabels.value["${type ?: ""}|${name ?: ""}"]

    val groups: StateFlow<List<ContactGroup>> = callbackFlow {
        val resolver = getApplication<Application>().contentResolver
        val observer = object : android.database.ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                launch { send(fetchGroups()) }
            }
        }
        resolver.registerContentObserver(ContactsContract.Groups.CONTENT_URI, true, observer)
        send(fetchGroups())
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun fetchGroups(): List<ContactGroup> {
        val resolver = getApplication<Application>().contentResolver
        val uri = ContactsContract.Groups.CONTENT_URI
        val projection = arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE)
        val list = mutableListOf<ContactGroup>()
        resolver.query(uri, projection, "${ContactsContract.Groups.GROUP_VISIBLE} = 1 AND ${ContactsContract.Groups.DELETED} = 0", null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Groups._ID)
            val titleIdx = cursor.getColumnIndexOrThrow(ContactsContract.Groups.TITLE)
            while (cursor.moveToNext()) {
                list.add(ContactGroup(cursor.getLong(idIdx), cursor.getString(titleIdx) ?: "Unnamed"))
            }
        }
        return list.sortedByNameLocale { it.name }
    }

    val contactGroupMemberships: StateFlow<List<ContactGroupMembership>> = contacts.map { contactList ->
        contactList.flatMap { contact ->
            contact.details.groups.map { membership ->
                ContactGroupMembership(contactId = contact.id, groupId = membership.groupId)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _accounts = MutableStateFlow<List<ContactAccount>>(emptyList())
    val accounts: StateFlow<List<ContactAccount>> = _accounts.asStateFlow()

    private val _lastSelectedAccount = MutableStateFlow<ContactAccount?>(null)

    // Parsed-VCF state for the import screen. null = not yet parsed (or cleared);
    // empty list = parsed and found nothing; non-empty = parsed contacts ready to import.
    private val _parsedVcfContacts = MutableStateFlow<List<Contact>?>(null)
    val parsedVcfContacts: StateFlow<List<Contact>?> = _parsedVcfContacts.asStateFlow()

    val isCalendarSyncEnabled: StateFlow<Boolean> = dataStore.booleanFlow("calendar_sync_enabled")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val showAccountLabels: StateFlow<Boolean> = dataStore.booleanFlow("show_account_labels")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    // Coalesces system-contact change notifications; collected with debounce so a
    // burst of provider writes triggers a single re-sync instead of one per change.
    private val syncTrigger = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // Fires on any ContactsContract change (contact/raw/data/account/group). Kept as a
    // field so onCleared() can unregister it and avoid leaking the ContentResolver.
    private val contactsObserver = object : android.database.ContentObserver(
        android.os.Handler(android.os.Looper.getMainLooper())
    ) {
        override fun onChange(selfChange: Boolean) { syncTrigger.tryEmit(Unit) }
    }

    init {
        // notifyForDescendants=true on the top-level authority so any contact/raw/data/
        // account change fires it; debounced so a sync burst causes a single reload.
        getApplication<Application>().contentResolver
            .registerContentObserver(ContactsContract.AUTHORITY_URI, true, contactsObserver)
        viewModelScope.launch {
            // Load immediately so cold-launched screens like InsertOrEdit don't
            // show empty list while waiting for the debounce.
            syncFromSystem()
            syncTrigger.debounce(300).collectLatest { syncFromSystem() }
        }
        loadAccounts()
        loadLastSelectedAccount()
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(contactsObserver)
        super.onCleared()
    }

    override fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * In-memory search over the provider-backed contact list. Splits the query
     * into whitespace-separated tokens; a contact matches when every token is a
     * case-insensitive substring of its searchable text (names, nicknames, phone
     * numbers, emails, notes, organizations). An empty query returns the full list.
     */
    private fun filterBySearch(list: List<Contact>, query: String): List<Contact> {
        val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return list
        return list.filter { contact ->
            val haystack = buildString {
                append(contact.details.names.joinToString(" ") { it.value }); append(' ')
                append(contact.details.nicknames.joinToString(" ") { it.nickname }); append(' ')
                append(contact.details.phoneNumbers.joinToString(" ") { it.number }); append(' ')
                append(contact.details.emails.joinToString(" ") { it.address }); append(' ')
                append(contact.details.notes.joinToString(" ") { it.content }); append(' ')
                append(contact.details.orgs.joinToString(" ") { it.company })
            }.lowercase()
            tokens.all { haystack.contains(it) }
        }
    }

    fun setCalendarSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setBoolean("calendar_sync_enabled", enabled)
            if (enabled) {
                withContext(Dispatchers.IO) {
                    CalendarSyncHelper.syncAll(getApplication())
                }
                CalendarWorker.schedule(getApplication())
            } else {
                withContext(Dispatchers.IO) {
                    CalendarSyncHelper.removeCalendar(getApplication())
                }
                CalendarWorker.cancel(getApplication())
            }
        }
    }

    fun setShowAccountLabels(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setBoolean("show_account_labels", enabled)
        }
    }

    /** Requests a debounced re-sync from the system contacts provider. */
    fun loadContacts() {
        syncTrigger.tryEmit(Unit)
    }

    private suspend fun syncFromSystem() = withContext(Dispatchers.IO) {
        try {
            val app = getApplication<Application>()
            val device = com.vayunmathur.contacts.data.Contact.getAllContacts(app)
            val sim = SimContactsDataSource.simContactsAsContacts(app)
            _allContacts.value = device + sim
            // Refresh SIM account labels for UI (type|name -> display)
            val infos = SimContactsDataSource.getSimSubscriptionInfos(app)
            val labels = infos.associate { info ->
                val acc = ContactAccount(SimContactsDataSource.accountNameFor(info), SIM_ACCOUNT_TYPE)
                "${acc.type}|${acc.name}" to SimContactsDataSource.getSimAccountDisplayLabel(info)
            }
            _simAccountLabels.value = labels
            // Also refresh the accounts list to include current SIM accounts (in case SIM inserted/removed)
            // Do it by launching loadAccounts() if needed; but we can update _accounts directly
            // to avoid double query. However loadAccounts() also merges DataStore saved accounts,
            // so we trigger it.
            // Use a direct call to avoid extra launch overhead: we are already on IO, but
            // loadAccounts() launches its own coroutine, so just trigger it.
            // To keep accounts in sync, launch a refresh:
            launch { loadAccountsInternal() }
        } catch (e: Exception) {
            Log.e("ContactViewModel", "Error loading contacts", e)
        }
    }

    private suspend fun loadAccountsInternal() {
        val app = getApplication<Application>()
        val uri = ContactsContract.RawContacts.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.RawContacts.ACCOUNT_NAME,
            ContactsContract.RawContacts.ACCOUNT_TYPE
        )
        val accountSet = mutableSetOf<ContactAccount>()
        try {
            val cursor = app.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: ""
                    val type = it.getString(1) ?: ""
                    accountSet.add(ContactAccount(name, type))
                }
            }
        } catch (e: Exception) {
            Log.e("ContactViewModel", "Error querying raw contacts for accounts", e)
        }
        val legacyAccounts = dataStore.getString("extra_accounts").orEmpty()
        if (legacyAccounts.isNotBlank()) {
            legacyAccounts.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                dataStore.addStringToSetIfAbsent("extra_accounts_set", it)
            }
            dataStore.setString("extra_accounts", "")
        }
        val savedAccounts = dataStore.stringSetFlow("extra_accounts_set").first().mapNotNull { entry ->
            val parts = entry.split("|")
            parts.firstOrNull()?.takeIf { it.isNotEmpty() }?.let { name ->
                ContactAccount(name, parts.getOrElse(1) { LOCAL_ACCOUNT_TYPE })
            }
        }
        // Virtual SIM accounts
        val simInfos = SimContactsDataSource.getSimSubscriptionInfos(app)
        val simAccounts = simInfos.map { info ->
            ContactAccount(SimContactsDataSource.accountNameFor(info), SIM_ACCOUNT_TYPE)
        }
        val simLabels = simInfos.associate { info ->
            val acc = ContactAccount(SimContactsDataSource.accountNameFor(info), SIM_ACCOUNT_TYPE)
            "${acc.type}|${acc.name}" to SimContactsDataSource.getSimAccountDisplayLabel(info)
        }
        _simAccountLabels.value = simLabels

        // Sort: SIM accounts by display label, others by name
        val all = (accountSet + savedAccounts + simAccounts).toList()
        val collator = ContactSorting.collator()
        _accounts.value = all.sortedWith(compareBy(collator) { acc ->
            if (acc.type == SIM_ACCOUNT_TYPE) _simAccountLabels.value["${acc.type}|${acc.name}"] ?: acc.name
            else acc.name
        })
    }

    fun loadAccounts() {
        viewModelScope.launch(Dispatchers.IO) {
            loadAccountsInternal()
        }
    }

    fun loadLastSelectedAccount() {
        val name = dataStore.getString("last_account_name")
        val type = dataStore.getString("last_account_type")
        _lastSelectedAccount.value = ContactAccount(name.orEmpty(), type.orEmpty())
    }

    fun setLastSelectedAccount(name: String, type: String) {
        viewModelScope.launch {
            dataStore.setString("last_account_name", name)
            dataStore.setString("last_account_type", type)
            _lastSelectedAccount.value = ContactAccount(name, type)
        }
    }

    fun setAccountVisibility(account: ContactAccount, visible: Boolean) {
        val key = "${account.type}|${account.name}"
        if (visible) {
            dataStore.removeStringFromSet("hidden_accounts", key)
            // Also clear legacy entry if it exists (migration)
            dataStore.removeStringFromSet("hidden_accounts", account.name)
        } else {
            dataStore.addStringToSet("hidden_accounts", key)
        }
    }

    // Backward-compatible overload used by old call sites that only knew name
    fun setAccountVisibility(accountName: String, visible: Boolean) {
        // Try to resolve type from current accounts list
        val matched = _accounts.value.firstOrNull { it.name == accountName }
        if (matched != null) {
            setAccountVisibility(matched, visible)
        } else {
            // Fallback: treat as legacy name-only key
            if (visible) dataStore.removeStringFromSet("hidden_accounts", accountName)
            else dataStore.addStringToSet("hidden_accounts", accountName)
        }
    }

    fun createAccount(name: String, type: String = LOCAL_ACCOUNT_TYPE, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            if (dataStore.addStringToSetIfAbsent("extra_accounts_set", "$name|$type")) {
                loadAccounts()
            }
            withContext(Dispatchers.Main) {
                onComplete?.invoke()
            }
        }
    }

    /**
     * Renames a local (on-device) account. Guards that [account] is a local account and that
     * [newName] is non-blank and does not collide with an existing account of the same type.
     * Re-points every RawContact stored under the old name to [newName] and migrates the
     * DataStore references (saved-accounts set, hidden-accounts key, last-selected account).
     * [onResult] is invoked on the main thread with success and an optional error message key.
     */
    /**
     * WHERE clause (+ selection args) matching a RawContacts account. An empty
     * type/name is treated as NULL-or-empty in the provider, so device-local
     * accounts (whose ACCOUNT_TYPE/ACCOUNT_NAME may be stored as NULL) are matched.
     */
    private fun accountSelection(type: String, name: String): Pair<String, Array<String>> {
        val args = ArrayList<String>()
        val typeClause = if (type.isEmpty()) {
            "(${ContactsContract.RawContacts.ACCOUNT_TYPE} IS NULL OR ${ContactsContract.RawContacts.ACCOUNT_TYPE} = '')"
        } else {
            args.add(type)
            "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?"
        }
        val nameClause = if (name.isEmpty()) {
            "(${ContactsContract.RawContacts.ACCOUNT_NAME} IS NULL OR ${ContactsContract.RawContacts.ACCOUNT_NAME} = '')"
        } else {
            args.add(name)
            "${ContactsContract.RawContacts.ACCOUNT_NAME} = ?"
        }
        return "$typeClause AND $nameClause" to args.toTypedArray()
    }

    fun renameLocalAccount(
        account: ContactAccount,
        newName: String,
        onResult: ((Boolean, String?) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            val trimmed = newName.trim()
            if (!isLocalAccountType(account.type)) {
                withContext(Dispatchers.Main) { onResult?.invoke(false, "not_local") }
                return@launch
            }
            if (isDefaultLocalAccount(account.name, account.type)) {
                withContext(Dispatchers.Main) { onResult?.invoke(false, "not_local") }
                return@launch
            }
            if (trimmed.isEmpty()) {
                withContext(Dispatchers.Main) { onResult?.invoke(false, "blank") }
                return@launch
            }
            if (trimmed == account.name) {
                withContext(Dispatchers.Main) { onResult?.invoke(true, null) }
                return@launch
            }
            val collides = _accounts.value.any { it.type == account.type && it.name == trimmed }
            if (collides) {
                withContext(Dispatchers.Main) { onResult?.invoke(false, "collision") }
                return@launch
            }
            withContext(Dispatchers.IO) {
                try {
                    val resolver = getApplication<Application>().contentResolver
                    val ops = ArrayList<ContentProviderOperation>()
                    val (sel, args) = accountSelection(account.type, account.name)
                    ops.add(
                        ContentProviderOperation.newUpdate(ContactsContract.RawContacts.CONTENT_URI)
                            .withSelection(sel, args)
                            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, trimmed)
                            .build()
                    )
                    resolver.applyBatch(ContactsContract.AUTHORITY, ops)
                } catch (e: Exception) {
                    Log.e("ContactViewModel", "Error renaming local account", e)
                }
                // Migrate the saved-accounts label so an empty account (no contacts) is renamed too.
                dataStore.removeStringFromSet("extra_accounts_set", "${account.name}|${account.type}")
                dataStore.addStringToSetIfAbsent("extra_accounts_set", "$trimmed|${account.type}")
                // Migrate the hidden-accounts visibility key ("type|name").
                val hidden = dataStore.getStringSetAwait("hidden_accounts")
                val oldHiddenKey = "${account.type}|${account.name}"
                if (oldHiddenKey in hidden) {
                    dataStore.removeStringFromSet("hidden_accounts", oldHiddenKey)
                    dataStore.addStringToSet("hidden_accounts", "${account.type}|$trimmed")
                }
                // Migrate last-selected (the implicit default save location).
                if (dataStore.getString("last_account_type") == account.type &&
                    dataStore.getString("last_account_name") == account.name
                ) {
                    dataStore.setString("last_account_name", trimmed)
                    _lastSelectedAccount.value = ContactAccount(trimmed, account.type)
                }
            }
            loadAccounts()
            loadContacts()
            withContext(Dispatchers.Main) { onResult?.invoke(true, null) }
        }
    }

    /**
     * Deletes a local (on-device) account and all of its contacts. Guards that [account] is a
     * local account. Hard-deletes every RawContact for the account (via the
     * CALLER_IS_SYNCADAPTER URI param, since the local account has no sync adapter) and clears
     * the DataStore references. [onResult] is invoked on the main thread.
     */
    fun deleteLocalAccount(
        account: ContactAccount,
        onResult: ((Boolean, String?) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            if (!isLocalAccountType(account.type)) {
                withContext(Dispatchers.Main) { onResult?.invoke(false, "not_local") }
                return@launch
            }
            if (isDefaultLocalAccount(account.name, account.type)) {
                withContext(Dispatchers.Main) { onResult?.invoke(false, "not_local") }
                return@launch
            }
            withContext(Dispatchers.IO) {
                try {
                    val resolver = getApplication<Application>().contentResolver
                    val uri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                        .build()
                    val (sel, args) = accountSelection(account.type, account.name)
                    resolver.delete(uri, sel, args)
                } catch (e: Exception) {
                    Log.e("ContactViewModel", "Error deleting local account", e)
                }
                dataStore.removeStringFromSet("extra_accounts_set", "${account.name}|${account.type}")
                dataStore.removeStringFromSet("hidden_accounts", "${account.type}|${account.name}")
                // Legacy name-only hidden key.
                dataStore.removeStringFromSet("hidden_accounts", account.name)
                // If this account was the default save location, reset to on-device.
                if (dataStore.getString("last_account_type") == account.type &&
                    dataStore.getString("last_account_name") == account.name
                ) {
                    dataStore.setString("last_account_name", "")
                    dataStore.setString("last_account_type", "")
                    _lastSelectedAccount.value = ContactAccount("", "")
                }
            }
            loadAccounts()
            loadContacts()
            withContext(Dispatchers.Main) { onResult?.invoke(true, null) }
        }
    }

    /** Parses every [uris] off the main thread and exposes the result via [parsedVcfContacts]. */
    fun parseVcfUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) {
            _parsedVcfContacts.value = emptyList()
            return
        }
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val allContacts = mutableListOf<Contact>()
            uris.forEach { uri ->
                try {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        allContacts.addAll(VcfUtils.parseContacts(input))
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ContactViewModel", "Error parsing VCF file: $uri", e)
                }
            }
            _parsedVcfContacts.value = allContacts
        }
    }

    /** Clears any parsed-VCF state held in the VM (called when the import screen dismisses). */
    fun clearParsedVcf() {
        _parsedVcfContacts.value = null
    }

    /**
     * Bulk-imports the previously parsed [contacts] into the account with [accountName] and [accountType].
     * Runs off the main thread; invokes [onDone] on the main thread when complete (or on failure).
     */
    fun importVcfContacts(
        contacts: List<Contact>,
        accountName: String,
        accountType: String,
        onDone: () -> Unit = {},
    ) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    // If target is a SIM account, route each contact via SIM data source
                    if (isSimAccountType(accountType)) {
                        val subId = accountName.toIntOrNull()
                        contacts.forEach { contact ->
                            val name = contact.name.value.trim().ifEmpty { contact.details.phoneNumbers.firstOrNull()?.number ?: "" }
                            val number = contact.details.phoneNumbers.firstOrNull()?.number?.trim() ?: ""
                            val email = contact.details.emails.firstOrNull()?.address?.trim()?.takeIf { it.isNotEmpty() }
                            if (name.isNotBlank() || number.isNotBlank()) {
                                SimContactsDataSource.insertSimContact(app, name, number, email, subId)
                            }
                        }
                        // Refresh unified list
                        withContext(Dispatchers.Main) { loadContacts() }
                    } else {
                        contacts.forEach { contact ->
                            val toSave = contact.copy(
                                accountName = accountName,
                                accountType = accountType
                            )
                            toSave.save(app, toSave.details, ContactDetails.empty())
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ContactViewModel", "Error importing contacts", e)
                }
            }
            loadContacts()
            onDone()
        }
    }

    fun getContact(contactId: Long): Contact? {
        return contacts.value.find { it.id == contactId } ?: _allContacts.value.find { it.id == contactId }
    }

    override fun deleteContact(contact: com.vayunmathur.contacts.data.Contact) {
        viewModelScope.launch(Dispatchers.IO) {
            if (isSimAccountType(contact.accountType)) {
                val sc = SimContactsDataSource.findBackingSimContact(getApplication(), contact)
                    ?: SimContact(-1, contact.name.value, contact.details.phoneNumbers.firstOrNull()?.number ?: "", contact.details.emails.firstOrNull()?.address, contact.accountName?.toIntOrNull())
                val toDelete = if (sc.subscriptionId == null) sc.copy(subscriptionId = contact.accountName?.toIntOrNull()) else sc
                SimContactsDataSource.deleteSimContact(getApplication(), toDelete)
                syncFromSystem()
            } else {
                com.vayunmathur.contacts.data.Contact.delete(getApplication(), contact)
                if (isCalendarSyncEnabled.value) {
                    CalendarSyncHelper.syncContact(getApplication(), contact.copy(details = contact.details.copy(dates = emptyList())))
                }
            }
            // The system-contacts ContentObserver picks up device deletes and re-syncs; SIM path already synced.
        }
    }

    // Groups Management
    override fun addGroup(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val values = android.content.ContentValues().apply {
                put(ContactsContract.Groups.TITLE, name)
                put(ContactsContract.Groups.GROUP_VISIBLE, 1)
            }
            resolver.insert(ContactsContract.Groups.CONTENT_URI, values)
        }
    }

    override fun deleteGroup(groupId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            resolver.delete(ContentUris.withAppendedId(ContactsContract.Groups.CONTENT_URI, groupId), null, null)
        }
    }

    fun addContactsToGroup(contactIds: List<Long>, groupId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            // Filter out SIM contacts (they don't support groups)
            val deviceIds = contactIds.filter { id ->
                val c = _allContacts.value.find { it.id == id }
                c == null || !isSimAccountType(c.accountType)
            }
            if (deviceIds.isEmpty()) return@launch
            val resolver = getApplication<Application>().contentResolver
            val ops = ArrayList<ContentProviderOperation>()
            deviceIds.forEach { contactId ->
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, contactId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId)
                    .build())
            }
            try {
                resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            } catch (e: Exception) {
                Log.e("ContactViewModel", "Error adding contacts to group", e)
            }
        }
    }

    fun removeContactsFromGroup(contactIds: List<Long>, groupId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val ops = ArrayList<ContentProviderOperation>()
            contactIds.forEach { contactId ->
                ops.add(ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                    .withSelection("${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ?", 
                        arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, groupId.toString()))
                    .build())
            }
            try {
                resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            } catch (e: Exception) {
                Log.e("ContactViewModel", "Error removing contacts from group", e)
            }
        }
    }

    fun getContactsForGroup(groupId: Long): Flow<List<Contact>> {
        return combine(contacts, contactGroupMemberships) { contacts, memberships ->
            val contactIds = memberships.filter { it.groupId == groupId }.map { it.contactId }
            contacts.filter { it.id in contactIds }
        }
    }

    fun getContactFlow(contactId: Long): Flow<Contact?> {
        return contacts.map { contacts -> contacts.find { it.id == contactId } ?: _allContacts.value.find { it.id == contactId } }
    }

    override fun saveContact(contact: com.vayunmathur.contacts.data.Contact) {
        viewModelScope.launch(Dispatchers.IO) { persistContact(contact) }
    }

    /** Writes [contact] to the SIM or the contacts provider. Returns false if the write failed. */
    private suspend fun persistContact(contact: com.vayunmathur.contacts.data.Contact): Boolean {
        if (isSimAccountType(contact.accountType)) {
            val subId = contact.accountName?.toIntOrNull()
            val name = contact.name.value.trim().ifEmpty { contact.nickname.nickname.trim().ifEmpty { contact.details.phoneNumbers.firstOrNull()?.number?.trim() ?: "" } }
            val number = contact.details.phoneNumbers.firstOrNull()?.number?.trim() ?: ""
            val email = contact.details.emails.firstOrNull()?.address?.trim()?.takeIf { it.isNotEmpty() }
            if (name.isBlank() && number.isBlank()) {
                Log.w("ContactViewModel", "SIM save skipped: name and number empty")
                return false
            }
            val isExisting = contact.id < 0
            var oldSc: SimContact? = null
            if (isExisting) {
                oldSc = SimContactsDataSource.listSimContacts(getApplication()).firstOrNull { SimContactsDataSource.syntheticIdFor(it) == contact.id }
                if (oldSc == null) oldSc = SimContactsDataSource.findBackingSimContact(getApplication(), contact)
                if (oldSc != null) {
                    // Skip if no actual change
                    if (oldSc.name == name && oldSc.number == number && oldSc.emails == email && oldSc.subscriptionId == subId) {
                        return true
                    }
                    SimContactsDataSource.deleteSimContact(getApplication(), oldSc)
                }
            }
            val ok = SimContactsDataSource.insertSimContact(getApplication(), name, number, email, subId)
            if (!ok) Log.e("ContactViewModel", "Failed to insert SIM contact")
            syncFromSystem()
            return ok
        }
        val contactId = contact.id
        val details = contact.details
        val oldDetails = contacts.value.find { it.id == contactId }?.details
            ?: _allContacts.value.find { it.id == contactId }?.details
            ?: com.vayunmathur.contacts.data.ContactDetails.empty()
        return contact.save(getApplication(), details, oldDetails)
    }

    // ---------------------------------------------------------------------
    // Base64 photo decode cache.
    // ---------------------------------------------------------------------

    private val photoCache = LruCache<String, Bitmap>(32)

    /**
     * Returns the decoded [Bitmap] for the Base64-encoded contact photo, or
     * `null` if decoding fails. Decodes at most once per unique input string
     * across the entire app lifetime (subject to LRU eviction).
     */
    @Synchronized
    override fun decodePhoto(base64: String): Bitmap? {
        photoCache.get(base64)?.let { return it }
        return try {
            val bytes = Base64.decode(base64)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also {
                photoCache.put(base64, it)
            }
        } catch (e: Exception) {
            Log.e("ContactViewModel", "Error decoding contact photo", e)
            null
        }
    }

    // ---------------------------------------------------------------------
    // EditContactPage form draft state.
    // ---------------------------------------------------------------------

    data class ContactDraft(
        val namePrefix: String = "",
        val firstName: String = "",
        val middleName: String = "",
        val lastName: String = "",
        val nameSuffix: String = "",
        val company: String = "",
        val noteContent: String = "",
        val nickname: String = "",
        val photo: Photo? = null,
        val birthday: LocalDate? = null,
        val accountName: String = "",
        val accountType: String = "",
        val phoneNumbers: List<PhoneNumber> = emptyList(),
        val emails: List<Email> = emptyList(),
        val dates: List<Event> = emptyList(),
        val addresses: List<Address> = emptyList(),
        val groupMemberships: List<GroupMembership> = emptyList(),
    )

    private val _editDraft = MutableStateFlow<ContactDraft?>(null)
    val editDraft: StateFlow<ContactDraft?> = _editDraft.asStateFlow()

    /** Original contact loaded into the current draft, if any. */
    private var editingOriginal: Contact? = null
    /** Tracks which contactId the draft was initialized for. `null` = new contact. */
    private var editingContactId: Long? = null
    /** True once a draft has been initialized at all (distinguishes "new contact" from "uninitialized"). */
    private var editingInitialized: Boolean = false

    private fun normalizePhoneForCompare(raw: String): String {
        return raw.filter { it.isDigit() || it == '+' }.trim()
    }

    fun initEditDraft(
        contactId: Long?,
        prefill: ContactPrefill? = null,
    ) {
        if (editingInitialized && editingContactId == contactId && _editDraft.value != null) return
        val contact = contactId?.let {
            getContact(it) ?: Contact.getContact(getApplication(), it) ?: _allContacts.value.find { c -> c.id == it }
        }
        val details = contact?.details
        editingOriginal = contact
        editingContactId = contactId
        editingInitialized = true
        _editDraft.value = ContactDraft(
            namePrefix = contact?.name?.namePrefix ?: "",
            firstName = contact?.name?.firstName ?: prefill?.name ?: "",
            middleName = contact?.name?.middleName ?: "",
            lastName = contact?.name?.lastName ?: "",
            nameSuffix = contact?.name?.nameSuffix ?: "",
            company = contact?.org?.company ?: prefill?.company ?: "",
            noteContent = contact?.note?.content ?: prefill?.notes ?: "",
            nickname = contact?.nickname?.nickname ?: prefill?.nickname ?: "",
            photo = contact?.photo,
            birthday = contact?.birthday?.startDate,
            accountName = contact?.accountName ?: _lastSelectedAccount.value?.name ?: "",
            accountType = contact?.accountType ?: _lastSelectedAccount.value?.type ?: "",
            phoneNumbers = mergePhones(details?.phoneNumbers ?: emptyList(), prefill?.phones ?: emptyList()),
            emails = mergeEmails(details?.emails ?: emptyList(), prefill?.emails ?: emptyList()),
            dates = details?.dates ?: emptyList(),
            addresses = mergeAddresses(details?.addresses ?: emptyList(), prefill?.postals ?: emptyList()),
            groupMemberships = details?.groups ?: emptyList(),
        )
    }

    /** Appends prefilled phones that aren't already present (compared by normalized number). */
    private fun mergePhones(existing: List<PhoneNumber>, prefill: List<PrefillValue>): List<PhoneNumber> {
        if (prefill.isEmpty()) return existing
        val result = existing.toMutableList()
        for (p in prefill) {
            val norm = normalizePhoneForCompare(p.value)
            if (norm.isEmpty()) continue
            if (result.any { normalizePhoneForCompare(it.number) == norm }) continue
            result += PhoneNumber(0, p.value, p.type ?: CDKPhone.TYPE_MOBILE, p.label ?: "")
        }
        return result
    }

    /** Appends prefilled emails that aren't already present (case-insensitive address match). */
    private fun mergeEmails(existing: List<Email>, prefill: List<PrefillValue>): List<Email> {
        if (prefill.isEmpty()) return existing
        val result = existing.toMutableList()
        for (e in prefill) {
            val v = e.value.trim()
            if (v.isEmpty()) continue
            if (result.any { it.address.trim().equals(v, ignoreCase = true) }) continue
            result += Email(0, e.value, e.type ?: CDKEmail.TYPE_HOME, e.label ?: "")
        }
        return result
    }

    /** Appends prefilled postal addresses that aren't already present (case-insensitive match). */
    private fun mergeAddresses(existing: List<Address>, prefill: List<PrefillValue>): List<Address> {
        if (prefill.isEmpty()) return existing
        val result = existing.toMutableList()
        for (a in prefill) {
            val v = a.value.trim()
            if (v.isEmpty()) continue
            if (result.any { it.formattedAddress.trim().equals(v, ignoreCase = true) }) continue
            result += Address(0, a.value, a.type ?: CDKStructuredPostal.TYPE_HOME, a.label ?: "")
        }
        return result
    }

    /**
     * Used by INSERT_OR_EDIT / SHOW_OR_CREATE_CONTACT flow when the user picks an existing
     * contact from the dialer. Directly appends [phone] (if not already present) and saves,
     * without opening the full editor.
     */
    fun addPhoneNumberToContact(
        contactId: Long,
        phone: String,
        type: Int = CDKPhone.TYPE_MOBILE,
        onComplete: () -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val existing = Contact.getContact(app, contactId) ?: _allContacts.value.find { it.id == contactId } ?: contacts.value.find { it.id == contactId }
                if (existing == null) {
                    withContext(Dispatchers.Main) { onComplete() }
                    return@launch
                }
                if (isSimAccountType(existing.accountType)) {
                    val subId = existing.accountName?.toIntOrNull()
                    val normalizedNew = normalizePhoneForCompare(phone)
                    val alreadyHas = existing.details.phoneNumbers.any { normalizePhoneForCompare(it.number) == normalizedNew }
                    if (alreadyHas) {
                        withContext(Dispatchers.Main) { onComplete() }
                        return@launch
                    }
                    val name = existing.name.value
                    if (existing.details.phoneNumbers.isEmpty()) {
                        val oldSc = SimContactsDataSource.findBackingSimContact(app, existing)
                        if (oldSc != null) SimContactsDataSource.deleteSimContact(app, oldSc)
                        SimContactsDataSource.insertSimContact(app, name.ifEmpty { phone }, phone, null, subId)
                    } else {
                        // SIM can hold only one number; store the new number as an additional SIM entry
                        SimContactsDataSource.insertSimContact(app, name.ifEmpty { phone }, phone, null, subId)
                    }
                    syncFromSystem()
                } else {
                    val normalizedNew = normalizePhoneForCompare(phone)
                    val alreadyHas = existing.details.phoneNumbers.any { normalizePhoneForCompare(it.number) == normalizedNew }
                    if (alreadyHas) {
                        withContext(Dispatchers.Main) { onComplete() }
                        return@launch
                    }
                    val newDetails = existing.details.copy(
                        phoneNumbers = existing.details.phoneNumbers + PhoneNumber(0, phone, type)
                    )
                    existing.save(app, newDetails, existing.details)
                }
            } catch (e: Exception) {
                Log.e("ContactViewModel", "Failed to add phone to contact $contactId", e)
            }
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    /** Applies [transform] to the current draft, if any. */
    fun updateEditDraft(transform: (ContactDraft) -> ContactDraft) {
        val current = _editDraft.value ?: return
        _editDraft.value = transform(current)
    }

    /** Clears the in-progress draft and forgets which contact was being edited. */
    fun clearEditDraft() {
        _editDraft.value = null
        editingOriginal = null
        editingContactId = null
        editingInitialized = false
    }

    /**
     * Decodes the picked image URI off the main thread, scales it to 500x500,
     * Base64-encodes it, and updates [editDraft]'s photo.
     */
    fun setEditDraftPhotoFromBitmap(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            val scaled = if (bitmap.width != 1024 || bitmap.height != 1024) {
                bitmap.scale(1024, 1024)
            } else bitmap
            val baos = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 100, baos)
            val encoded = Base64.encode(baos.toByteArray())
            updateEditDraft { draft ->
                val newPhoto = draft.photo?.withValue(encoded)
                    ?: com.vayunmathur.contacts.data.Photo(0, encoded)
                draft.copy(photo = newPhoto)
            }
        }
    }

    /**
     * Persists the current draft via the unified save path.
     * SIM vs device routing is handled by [saveContact] based on draft.accountType.
     */
    fun saveEditDraft(onResult: ((Boolean, String?) -> Unit)? = null) {
        val draft = _editDraft.value ?: return
        // For SIM accounts, validate SIM limits: at least name or phone needed, and SIM can't store extra fields
        if (isSimAccountType(draft.accountType)) {
            val nameVal = listOfNotNull(draft.namePrefix.ifEmpty { null }, draft.firstName.ifEmpty { null }, draft.middleName.ifEmpty { null }, draft.lastName.ifEmpty { null }, draft.nameSuffix.ifEmpty { null }).joinToString(" ").trim()
            val phoneVal = draft.phoneNumbers.firstOrNull()?.number?.trim() ?: ""
            if (nameVal.isEmpty() && phoneVal.isEmpty()) {
                onResult?.invoke(false, "Name or phone required")
                return
            }
        }
        val original = editingOriginal
        val birthdayId = original?.birthday?.id ?: 0L
        val datesWithoutBirthday = draft.dates.filter { it.type != CDKEvent.TYPE_BIRTHDAY }.toMutableList()
        draft.birthday?.let { bday ->
            datesWithoutBirthday += Event(birthdayId, bday, CDKEvent.TYPE_BIRTHDAY)
        }
        val details = ContactDetails(
            phoneNumbers = draft.phoneNumbers,
            emails = draft.emails,
            addresses = draft.addresses,
            dates = datesWithoutBirthday,
            photos = listOfNotNull(draft.photo),
            names = listOf(
                Name(
                    original?.name?.id ?: 0,
                    draft.namePrefix,
                    draft.firstName,
                    draft.middleName,
                    draft.lastName,
                    draft.nameSuffix
                )
            ),
            orgs = listOf(Organization(original?.org?.id ?: 0, draft.company)),
            notes = listOf(Note(original?.note?.id ?: 0, draft.noteContent)),
            nicknames = listOf(
                Nickname(
                    original?.nickname?.id ?: 0,
                    draft.nickname,
                    CDKNickname.TYPE_DEFAULT
                )
            ),
            groups = draft.groupMemberships
        )
        // For existing SIM contacts, keep synthetic id so saveContact can locate old row.
        // The provider requires an account to name both fields or neither, so a draft pointing at
        // a half-renamed account saves to device-local rather than being rejected.
        val accountType = draft.accountType.ifEmpty { null }
        val accountName = if (accountType == null) null else draft.accountName.ifEmpty { null }
        val newContact = original?.copy(
            accountType = accountType,
            accountName = accountName,
            details = details
        ) ?: Contact(
            id = 0,
            accountType = accountType,
            accountName = accountName,
            isFavorite = false,
            details = details
        )
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { persistContact(newContact) }
            if (ok) clearEditDraft()
            onResult?.invoke(ok, null)
        }
    }
}

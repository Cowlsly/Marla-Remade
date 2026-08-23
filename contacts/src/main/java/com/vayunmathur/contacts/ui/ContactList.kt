package com.vayunmathur.contacts.ui

import androidx.compose.ui.res.pluralStringResource
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKStructuredPostal
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactGroup
import com.vayunmathur.contacts.util.ContactListUiState
import com.vayunmathur.contacts.util.ContactSorting.groupKey
import com.vayunmathur.contacts.util.ContactSorting.sortedLocale
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.util.ContactsActions
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.vayunmathur.library.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.vayunmathur.contacts.util.VcfUtils
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.CommonSearchBar
import kotlinx.coroutines.launch
import android.content.Intent
import androidx.compose.ui.platform.LocalResources

/** Binds [ContactViewModel] to the stateless [ContactListScreen]. */
@Composable
fun ContactList(
    viewModel: ContactViewModel,
    backStack: NavBackStack<Route>,
    onContactClick: (Contact) -> Unit,
    onAddContactClick: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.loadContacts()
        viewModel.loadAccounts()
    }

    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val showAccountLabels by viewModel.showAccountLabels.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val hasLoadedContacts by viewModel.hasLoadedContacts.collectAsStateWithLifecycle()

    val last = backStack.last()

    ContactListScreen(
        state = ContactListUiState(
            contacts = contacts,
            groups = groups,
            searchQuery = searchQuery,
            showAccountLabels = showAccountLabels,
            openContactId = when (last) {
                is Route.ContactDetail -> last.contactId
                is Route.EditContact -> last.contactId
                else -> null
            },
            showAddButton = last !is Route.EditContact,
            isLoading = !hasLoadedContacts,
        ),
        actions = object : ContactsActions by viewModel {
            override fun openContact(contact: Contact) = onContactClick(contact)

            override fun addContact() = onAddContactClick()

            override fun addToGroup(contactIds: List<Long>) {
                backStack.add(Route.AddToGroupDialog(contactIds))
            }

            override fun shareContacts(contacts: List<Contact>, filename: String) {
                shareContactsAsVcf(scope, context, contacts, filename, resources.getString(R.string.share_contact))
            }
        },
    )
}

/**
 * The contact list, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListScreen(state: ContactListUiState, actions: ContactsActions) {
    val contacts = state.contacts
    val selectedIds = remember { mutableStateListOf<Long>() }
    val isSelectionMode = selectedIds.isNotEmpty()

    val (favorites, otherContacts) = remember(contacts) { contacts.partition { it.isFavorite } }
    val groupedContacts = remember(otherContacts) {
        otherContacts.groupBy { groupKey(it.name.value) }
            .mapValues { (_, c) -> c.sortedLocale() }
            .toSortedMap()
    }

    val toggleSelection = { id: Long ->
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
    }

    var showDeleteConfirmation by remember { mutableStateOf(false) }

    var isFocusableBySystem by remember { mutableStateOf(false) }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.delete_selected_contacts_title)) },
            text = { Text(pluralStringResource(R.plurals.delete_selected_contacts_confirm, selectedIds.size, selectedIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    val toDelete = contacts.filter { it.id in selectedIds }
                    toDelete.forEach { actions.deleteContact(it) }
                    selectedIds.clear()
                    showDeleteConfirmation = false
                }) {
                    Text(stringResource(UiR.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(UiR.string.cancel))
                }
            }
        )
    }

    val selectedID = state.openContactId

    androidx.activity.compose.BackHandler(enabled = state.searchQuery.isNotEmpty() && !isSelectionMode) {
        actions.setSearchQuery("")
    }

    androidx.activity.compose.BackHandler(enabled = isSelectionMode) {
        selectedIds.clear()
    }

    LazyListScaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text(stringResource(R.string.selected_count, selectedIds.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds.clear() }) {
                            IconClose()
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            actions.addToGroup(selectedIds.toList())
                        }) {
                            IconGroup()
                        }
                        IconButton(onClick = {
                            actions.shareContacts(contacts.filter { it.id in selectedIds }, "selected_contacts.vcf")
                        }) {
                            IconShare()
                        }
                        IconButton(onClick = { showDeleteConfirmation = true }) {
                            IconDelete()
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        CommonSearchBar(
                            value = state.searchQuery,
                            onValueChange = { actions.setSearchQuery(it) },
                            placeholder = stringResource(R.string.search_contacts),
                            padding = PaddingValues(0.dp),
                            modifier = Modifier
                                .focusProperties { canFocus = isFocusableBySystem }
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            awaitPointerEvent(PointerEventPass.Initial)
                                            if (!isFocusableBySystem) isFocusableBySystem = true
                                        }
                                    }
                                }
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused) {
                                        isFocusableBySystem = false
                                    }
                                }
                        )
                    },
                    actions = {
                        IconButton(onClick = { actions.shareContacts(contacts, "all_contacts.vcf") }) {
                            IconShare()
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if(state.showAddButton && !isSelectionMode) {
                FloatingActionButton(onClick = { actions.addContact() }) {
                    IconAdd()
                }
            }
        },
        horizontalPadding = 8.dp,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        scrollBehavior = appBarScrollBehavior(),
    ) {
        if (contacts.isEmpty()) {
            item(key = "contacts-empty") {
                when {
                    state.searchQuery.isNotEmpty() -> EmptyState(
                        title = stringResource(R.string.no_contacts_found),
                        modifier = Modifier.fillParentMaxSize(),
                    )
                    state.isLoading -> LoadingState(modifier = Modifier.fillParentMaxSize())
                    else -> EmptyState(
                        title = stringResource(R.string.no_contacts_yet),
                        modifier = Modifier.fillParentMaxSize(),
                        message = stringResource(R.string.no_contacts_yet_message),
                        icon = { IconPerson() },
                    )
                }
            }
        }

        if (favorites.isNotEmpty()) {
            item(key = "favorites-header") { FavoritesHeader() }
            item(key = "favorites-card") {
                GroupedContactSection(count = favorites.size) { idx ->
                    val contact = favorites[idx]
                    ContactItem(
                        contact = contact,
                        isSelected = if (isSelectionMode) contact.id in selectedIds else selectedID == contact.id,
                        showAccountLabels = state.showAccountLabels,
                        allGroups = state.groups,
                        decodePhoto = actions::decodePhoto,
                        embeddedInCard = true,
                        onClick = {
                            if (isSelectionMode) toggleSelection(contact.id) else actions.openContact(contact)
                        },
                        onLongClick = {
                            if (!isSelectionMode) selectedIds.add(contact.id)
                        }
                    )
                }
            }
        }

        groupedContacts.forEach { (letter, contactsInGroup) ->
            item(key = "letter-header-$letter") { LetterHeader(letter) }
            item(key = "letter-card-$letter") {
                GroupedContactSection(count = contactsInGroup.size) { idx ->
                    val contact = contactsInGroup[idx]
                    ContactItem(
                        contact = contact,
                        isSelected = if (isSelectionMode) contact.id in selectedIds else selectedID == contact.id,
                        showAccountLabels = state.showAccountLabels,
                        allGroups = state.groups,
                        decodePhoto = actions::decodePhoto,
                        embeddedInCard = true,
                        onClick = {
                            if (isSelectionMode) toggleSelection(contact.id) else actions.openContact(contact)
                        },
                        onLongClick = {
                            if (!isSelectionMode) selectedIds.add(contact.id)
                        }
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListPick(
    mimeType: String?,
    contacts: List<Contact>,
    allowMultiple: Boolean = false,
    selectedUris: List<Uri> = emptyList(),
    onConfirm: () -> Unit = {},
    onClick: (Uri) -> Unit,
) {
    val (favorites, otherContacts) = remember(contacts) { contacts.partition { it.isFavorite } }

    val groupedContacts = remember(otherContacts) {
        otherContacts
            .groupBy { groupKey(it.name.value) }
            .toSortedMap()
    }

    val selectedSet = selectedUris.toSet()

    LazyListScaffold(
        topBar = { TopAppBar({ Text(stringResource(R.string.app_name)) }) },
        floatingActionButton = {
            if (allowMultiple) {
                ExtendedFloatingActionButton(onClick = onConfirm) {
                    val label = stringResource(UiR.string.done) +
                        if (selectedUris.isNotEmpty()) " (${selectedUris.size})" else ""
                    Text(label)
                }
            }
        },
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        scrollBehavior = appBarScrollBehavior(),
    ) {
        if (favorites.isNotEmpty()) {
            item(key = "pick-favorites-header") { FavoritesHeader() }
            item(key = "pick-favorites-card") {
                GroupedContactSection(count = favorites.size) { idx ->
                    ContactItemPick(favorites[idx], mimeType, selectedSet, onClick)
                }
            }
        }

        groupedContacts.forEach { (letter, contactsInGroup) ->
            item(key = "pick-letter-header-$letter") { LetterHeader(letter) }
            item(key = "pick-letter-card-$letter") {
                GroupedContactSection(count = contactsInGroup.size) { idx ->
                    ContactItemPick(contactsInGroup[idx], mimeType, selectedSet, onClick)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactItemPick(contact: Contact, mimeType: String?, selectedUris: Set<Uri>, onClick: (Uri) -> Unit) {
    if (mimeType == null || mimeType == ContactsContract.Contacts.CONTENT_ITEM_TYPE || mimeType == ContactsContract.Contacts.CONTENT_TYPE) {
        val uri = Uri.withAppendedPath(ContactsContract.RawContacts.CONTENT_URI, contact.id.toString())
        ContactItem(
            contact = contact,
            isSelected = uri in selectedUris,
            showAccountLabels = true,
            onClick = { onClick(uri) }
        )
    } else {
        val details = contact.details
        val (relevantList, baseURI) = when(mimeType) {
            CDKEmail.CONTENT_ITEM_TYPE -> details.emails to CDKEmail.CONTENT_URI
            CDKPhone.CONTENT_ITEM_TYPE -> details.phoneNumbers to CDKPhone.CONTENT_URI
            CDKStructuredPostal.CONTENT_ITEM_TYPE -> details.addresses to CDKStructuredPostal.CONTENT_URI
            else -> throw IllegalArgumentException("Unsupported MIME type: $mimeType")
        }
        val itemUris = relevantList.map { Uri.withAppendedPath(baseURI, it.id.toString()) }
        ContactItem(
            contact = contact,
            isSelected = itemUris.any { it in selectedUris },
            showAccountLabels = true,
            onClick = {  },
            dropdownList = relevantList.map { it.value },
            dropdownListClick = { index -> onClick(itemUris[index]) }
        )
    }
}

@Composable
fun FavoritesHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconStar(tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = stringResource(R.string.favorites),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun LetterHeader(letter: Char, modifier: Modifier = Modifier) {
    Text(
        text = letter.toString(),
        modifier = modifier.padding(vertical = 8.dp, horizontal = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

fun getAvatarColor(id: Long): Color {
    val colors = listOf(
        Color(0xFF6C3800),
        Color(0xFF00502A),
        Color(0xFF8B0053),
        Color(0xFF891916),
        Color(0xFF004B5B),
        Color(0xFF5528A1),
    )
    val index = Math.floorMod(id, colors.size.toLong()).toInt()
    return colors[index]
}

@Composable
fun GroupedContactSection(
    count: Int,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    topAttached: Boolean = false,
    row: @Composable (index: Int) -> Unit,
) {
    if (count == 0) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until count) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = groupShape(i, count, flatTop = topAttached && i == 0),
                color = containerColor,
            ) { row(i) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ContactItem(
    contact: Contact,
    isSelected: Boolean,
    showAccountLabels: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    allGroups: List<ContactGroup> = emptyList(),
    decodePhoto: ((String) -> Bitmap?)? = null,
    onLongClick: (() -> Unit)? = null,
    dropdownList: List<String>? = null,
    dropdownListClick: (Int) -> Unit = {},
    embeddedInCard: Boolean = false
) {
    val combinedModifier = if (dropdownList == null) {
        modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    } else {
        modifier
    }

    val contactGroups = contactGroupsOf(contact, allGroups)

    val trimmedOrg = contact.org.company.trim()
    val showOrg = trimmedOrg.isNotEmpty()
    val showGroups = contactGroups.isNotEmpty()

    val content = @Composable {
        val hasDropdown = !dropdownList.isNullOrEmpty()

        key(showOrg, showGroups, contactGroups.size) {
            val itemModifier = if (embeddedInCard) {
                combinedModifier
            } else {
                val r = if (hasDropdown) 0.dp else 16.dp
                combinedModifier.clip(RoundedCornerShape(16.dp, 16.dp, r, r))
            }
            val rowContainerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                embeddedInCard -> Color.Transparent
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Row(
                modifier = itemModifier
                    .fillMaxWidth()
                    .background(rowContainerColor)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ContactAvatar(contact, decodePhoto, Modifier.size(50.dp))
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = contactDisplayName(contact),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (showOrg) {
                        Text(trimmedOrg)
                    }
                    if (showGroups) {
                        Text(
                            text = contactGroups.joinToString(", ") { it.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (showAccountLabels) {
                    Spacer(Modifier.width(16.dp))
                    val onDevice = stringResource(R.string.on_device)
                    Text(
                        text = contact.accountName ?: onDevice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp)
                    )
                }
            }
        }
    }

    if (dropdownList != null) {
        Column(modifier = modifier.fillMaxWidth()) {
            content()
            dropdownList.forEachIndexed { idx, it ->
                Spacer(Modifier.height(4.dp))
                ListItem(
                    content = {
                        Text(text = it)
                    },
                    modifier = Modifier.clickable {
                        dropdownListClick(idx)
                    }.clip(RoundedCornerShape(0.dp, 0.dp, if(idx == dropdownList.size - 1) 16.dp else 0.dp, if(idx == dropdownList.size - 1) 16.dp else 0.dp)),
                    colors = ListItemDefaults.colors(containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    }))
            }
        }
    } else {
        content()
    }
}

package com.vayunmathur.contacts.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.FormDetailGroup
import com.vayunmathur.library.ui.FormSection
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.InputChip
import com.vayunmathur.library.ui.LabeledTextField
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKStructuredPostal
import com.vayunmathur.contacts.data.Address
import com.vayunmathur.contacts.data.ContactDetail
import com.vayunmathur.contacts.data.ContactGroup
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.contacts.data.GroupMembership
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.Photo
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.formatDisplay
import com.vayunmathur.contacts.util.ContactAccount
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.library.ui.IconAddPhoto
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconEvent
import com.vayunmathur.library.ui.IconGroup
import com.vayunmathur.library.ui.IconMail
import com.vayunmathur.library.ui.IconRemoveCircle
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect
import kotlinx.datetime.LocalDate
import kotlin.io.encoding.Base64

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditContactPage(backStack: NavBackStack<Route>, viewModel: ContactViewModel, editRoute: Route.EditContact, onExit: () -> Unit = { backStack.pop() }) {
    val contactId = editRoute.contactId
    val context = LocalContext.current
    var saveError by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    // Initialize the VM draft for this contact. No-op on rotation (same key).
    LaunchedEffect(contactId) {
        viewModel.initEditDraft(
            contactId = contactId,
            prefill = editRoute.prefill,
        )
    }
    val draft by viewModel.editDraft.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val simLabels by viewModel.simAccountLabels.collectAsStateWithLifecycle()
    val isNewContact = contactId == null

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val encoded = java.net.URLEncoder.encode(uri.toString(), "UTF-8")
            backStack.add(Route.CropPhoto(encoded))
        }
    }

    val currentDraft = draft ?: return
    val isSimAccount = currentDraft.accountType == SIM_ACCOUNT_TYPE
    // Markdown note editor backed by the shared ODF editor; stored content stays markdown.
    val noteController = key(contactId) {
        com.vayunmathur.library.ui.rememberOdfMarkdownEditorController(initialMarkdown = currentDraft.noteContent) { content ->
            viewModel.updateEditDraft { it.copy(noteContent = content) }
        }
    }
    DetailScaffold(
        title = if (isNewContact) stringResource(R.string.add_contact) else stringResource(R.string.edit_contact),
        onClose = { onExit() },
        actions = {
            Button(onClick = {
                if (isSaving) return@Button
                isSaving = true
                viewModel.saveEditDraft { ok, err ->
                    isSaving = false
                    if (ok) onExit() else saveError = err ?: context.getString(R.string.save_failed)
                }
            }) {
                Text(stringResource(UiR.string.save))
            }
        },
        bottomBar = {
            if (noteController.focused) {
                com.vayunmathur.library.ui.OdfMarkdownEditorToolbar(noteController)
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (saveError != null) {
                Text(saveError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }

            // Account chooser for new contacts (SIM accounts appear here as normal accounts)
            if (isNewContact) {
                AccountChooser(currentDraft.accountName, currentDraft.accountType, accounts, simLabels) { name, type ->
                    viewModel.updateEditDraft { it.copy(accountName = name, accountType = type) }
                    viewModel.setLastSelectedAccount(name, type)
                }
                Spacer(Modifier.height(16.dp))
                if (isSimAccount) {
                    Text(
                        stringResource(R.string.sim_limited_fields_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                }
            } else if (isSimAccount) {
                Text(
                    stringResource(R.string.sim_limited_fields_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(8.dp))
            AddPictureSection(
                photo = currentDraft.photo?.photo,
                viewModel = viewModel,
                onClick = {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                removePhoto = {
                    viewModel.updateEditDraft { it.copy(photo = null) }
                }
            )
            Spacer(Modifier.height(24.dp))


            FormSection {
                LabeledTextField(
                    value = currentDraft.firstName,
                    onValueChange = { v -> viewModel.updateEditDraft { it.copy(firstName = v) } },
                    label = stringResource(R.string.first_name),
                    leadingIcon = {
                        NamePrefixChooser(currentDraft.namePrefix) { v ->
                            viewModel.updateEditDraft { it.copy(namePrefix = v) }
                        }
                    },
                )
                LabeledTextField(
                    value = currentDraft.middleName,
                    onValueChange = { v -> viewModel.updateEditDraft { it.copy(middleName = v) } },
                    label = stringResource(R.string.middle_name),
                )
                LabeledTextField(
                    value = currentDraft.lastName,
                    onValueChange = { v -> viewModel.updateEditDraft { it.copy(lastName = v) } },
                    label = stringResource(R.string.last_name),
                    trailingIcon = {
                        NameSuffixChooser(currentDraft.nameSuffix) { v ->
                            viewModel.updateEditDraft { it.copy(nameSuffix = v) }
                        }
                    },
                )
                LabeledTextField(
                    value = currentDraft.nickname,
                    onValueChange = { v -> viewModel.updateEditDraft { it.copy(nickname = v) } },
                    label = stringResource(R.string.nickname),
                )
                LabeledTextField(
                    value = currentDraft.company,
                    onValueChange = { v -> viewModel.updateEditDraft { it.copy(company = v) } },
                    label = stringResource(R.string.company),
                )
            }

            // Group memberships (placed near identity fields)
            val allGroups by viewModel.groups.collectAsStateWithLifecycle()
            val draftGroupIds = currentDraft.groupMemberships.map { it.groupId }.toSet()
            val memberGroups = allGroups.filter { it.id in draftGroupIds && it.name.trim().isNotEmpty() }
            val availableGroups = allGroups.filter { it.id !in draftGroupIds && it.name.trim().isNotEmpty() }

            GroupMembershipSection(
                memberGroups = memberGroups,
                availableGroups = availableGroups,
                onAddGroup = { groupId ->
                    viewModel.updateEditDraft { it.copy(
                        groupMemberships = it.groupMemberships + GroupMembership(0, groupId)
                    )}
                },
                onRemoveGroup = { groupId ->
                    viewModel.updateEditDraft { it.copy(
                        groupMemberships = it.groupMemberships.filter { gm -> gm.groupId != groupId }
                    )}
                }
            )
            Spacer(Modifier.height(16.dp))

            val phoneCtx = LocalContext.current
            FormDetailGroup(
                items = currentDraft.phoneNumbers,
                label = stringResource(R.string.phone),
                addLabel = stringResource(R.string.add_phone),
                typeOptions = listOf(CDKPhone.TYPE_MOBILE, CDKPhone.TYPE_HOME, CDKPhone.TYPE_WORK, CDKPhone.TYPE_OTHER, CDKPhone.TYPE_CUSTOM),
                value = { it.value },
                onValueChange = { idx, v -> viewModel.updateEditDraft { it.copy(phoneNumbers = it.phoneNumbers.toMutableList().also { l -> l[idx] = l[idx].withValue(v) }) } },
                typeLabel = { it.typeString(phoneCtx) },
                optionLabel = { opt -> ContactDetail.default<PhoneNumber>().withType(opt).typeString(phoneCtx) },
                onTypeChange = { idx, opt -> viewModel.updateEditDraft { it.copy(phoneNumbers = it.phoneNumbers.toMutableList().also { l -> l[idx] = l[idx].withType(opt) }) } },
                onRemove = { idx -> viewModel.updateEditDraft { it.copy(phoneNumbers = it.phoneNumbers.toMutableList().also { l -> l.removeAt(idx) }) } },
                onAdd = { viewModel.updateEditDraft { it.copy(phoneNumbers = it.phoneNumbers + ContactDetail.default<PhoneNumber>()) } },
                keyboardType = KeyboardType.Phone,
                isCustom = { it.type == CDKPhone.TYPE_CUSTOM },
                customLabel = { it.label },
                onLabelChange = { idx, v -> viewModel.updateEditDraft { it.copy(phoneNumbers = it.phoneNumbers.toMutableList().also { l -> l[idx] = l[idx].withLabel(v) }) } },
                customLabelText = stringResource(R.string.custom_label),
                customPlaceholder = stringResource(R.string.enter_custom_label),
                leadingIcon = { item -> Text(getCountryFlagEmoji(item.value)) },
                addIcon = { IconCall() },
            )
            Spacer(Modifier.height(8.dp))

            val emailCtx = LocalContext.current
            FormDetailGroup(
                items = currentDraft.emails,
                label = stringResource(R.string.email),
                addLabel = stringResource(R.string.add_email),
                typeOptions = listOf(CDKEmail.TYPE_HOME, CDKEmail.TYPE_WORK, CDKEmail.TYPE_OTHER, CDKEmail.TYPE_MOBILE, CDKEmail.TYPE_CUSTOM),
                value = { it.value },
                onValueChange = { idx, v -> viewModel.updateEditDraft { it.copy(emails = it.emails.toMutableList().also { l -> l[idx] = l[idx].withValue(v) }) } },
                typeLabel = { it.typeString(emailCtx) },
                optionLabel = { opt -> ContactDetail.default<Email>().withType(opt).typeString(emailCtx) },
                onTypeChange = { idx, opt -> viewModel.updateEditDraft { it.copy(emails = it.emails.toMutableList().also { l -> l[idx] = l[idx].withType(opt) }) } },
                onRemove = { idx -> viewModel.updateEditDraft { it.copy(emails = it.emails.toMutableList().also { l -> l.removeAt(idx) }) } },
                onAdd = { viewModel.updateEditDraft { it.copy(emails = it.emails + ContactDetail.default<Email>()) } },
                keyboardType = KeyboardType.Email,
                isCustom = { it.type == CDKEmail.TYPE_CUSTOM },
                customLabel = { it.label },
                onLabelChange = { idx, v -> viewModel.updateEditDraft { it.copy(emails = it.emails.toMutableList().also { l -> l[idx] = l[idx].withLabel(v) }) } },
                customLabelText = stringResource(R.string.custom_label),
                customPlaceholder = stringResource(R.string.enter_custom_label),
                addIcon = { IconMail() },
            )

            Spacer(Modifier.height(16.dp))

            Birthday(backStack, currentDraft.birthday) { v ->
                viewModel.updateEditDraft { it.copy(birthday = v) }
            }

            DateDetailsSection(
                backStack = backStack,
                details = currentDraft.dates,
                onDetailsChange = { list -> viewModel.updateEditDraft { it.copy(dates = list) } },
                icon = { IconEvent() },
                options = listOf(CDKEvent.TYPE_ANNIVERSARY, CDKEvent.TYPE_OTHER, CDKEvent.TYPE_CUSTOM)
            )

            Spacer(Modifier.height(12.dp))

            val addressCtx = LocalContext.current
            FormDetailGroup(
                items = currentDraft.addresses,
                label = stringResource(R.string.addresses),
                addLabel = stringResource(R.string.add_address),
                typeOptions = listOf(CDKStructuredPostal.TYPE_HOME, CDKStructuredPostal.TYPE_WORK, CDKStructuredPostal.TYPE_OTHER, CDKStructuredPostal.TYPE_CUSTOM),
                value = { it.value },
                onValueChange = { idx, v -> viewModel.updateEditDraft { it.copy(addresses = it.addresses.toMutableList().also { l -> l[idx] = l[idx].withValue(v) }) } },
                typeLabel = { it.typeString(addressCtx) },
                optionLabel = { opt -> ContactDetail.default<Address>().withType(opt).typeString(addressCtx) },
                onTypeChange = { idx, opt -> viewModel.updateEditDraft { it.copy(addresses = it.addresses.toMutableList().also { l -> l[idx] = l[idx].withType(opt) }) } },
                onRemove = { idx -> viewModel.updateEditDraft { it.copy(addresses = it.addresses.toMutableList().also { l -> l.removeAt(idx) }) } },
                onAdd = { viewModel.updateEditDraft { it.copy(addresses = it.addresses + ContactDetail.default<Address>()) } },
                isCustom = { it.type == CDKStructuredPostal.TYPE_CUSTOM },
                customLabel = { it.label },
                onLabelChange = { idx, v -> viewModel.updateEditDraft { it.copy(addresses = it.addresses.toMutableList().also { l -> l[idx] = l[idx].withLabel(v) }) } },
                customLabelText = stringResource(R.string.custom_label),
                customPlaceholder = stringResource(R.string.enter_custom_label),
                addIcon = { IconEvent() },
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.note),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            com.vayunmathur.library.ui.OdfMarkdownEditorField(
                controller = noteController,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun AccountChooser(
    accountName: String,
    accountType: String,
    accounts: List<ContactAccount>,
    simLabels: Map<String, String> = emptyMap(),
    onAccountChange: (String, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val onDevice = stringResource(R.string.on_device)
    val currentKey = "${accountType}|${accountName}"
    val displayValue = when {
        accountName.isEmpty() && accountType.isEmpty() -> onDevice
        simLabels.containsKey(currentKey) -> simLabels[currentKey]!!
        accountName.isNotEmpty() -> accountName
        else -> onDevice
    }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.account)) },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    IconArrowDropDown()
                }
            }
        )
        DropdownMenu(expanded, { expanded = false }) {
            if (accounts.none { it.name.isBlank() && it.type.isBlank() }) {
                DropdownMenuItem(
                    text = { Text(onDevice) },
                    onClick = {
                        onAccountChange("", "")
                        expanded = false
                    }
                )
            }
            accounts.forEach { account ->
                val key = "${account.type}|${account.name}"
                val label = simLabels[key] ?: account.name.ifEmpty { onDevice }
                DropdownMenuItem(
                    text = { Text(if (simLabels.containsKey(key)) label else stringResource(R.string.account_display_format, label, account.type)) },
                    onClick = {
                        onAccountChange(account.name, account.type)
                        expanded = false
                    }
                )
            }
        }
        Box(Modifier.matchParentSize().clickable { expanded = true })
    }
}

private fun getCountryFlagEmoji(phoneNumber: String): String {
    val phoneUtil = PhoneNumberUtil.getInstance()
    return try {
        val numberProto = phoneUtil.parse(phoneNumber, "")
        val regionCode = phoneUtil.getRegionCodeForNumber(numberProto)
        val firstLetter = Character.codePointAt(regionCode, 0) - 0x41 + 0x1F1E6
        val secondLetter = Character.codePointAt(regionCode, 1) - 0x41 + 0x1F1E6
        String(Character.toChars(firstLetter)) + String(Character.toChars(secondLetter))
    } catch (_: Exception) {
        ""
    }
}

val namePrefixes = listOf("None", "Dr", "Mr", "Mrs", "Ms")
val nameSuffixes = listOf("None", "Jr", "Sr", "I", "II", "III", "IV", "V")

@Composable
fun NamePrefixChooser(namePrefix: String, onNamePrefixChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) {
        Text(namePrefix)
        IconArrowDropDown()
        DropdownMenu(expanded, { expanded = false }) {
            namePrefixes.forEach { prefix ->
                DropdownMenuItem(text = { Text(prefix) }, onClick = {
                    onNamePrefixChange(if(prefix == "None") "" else prefix)
                    expanded = false
                })
            }
        }
    }
}

@Composable
fun NameSuffixChooser(nameSuffix: String, onNameSuffixChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) {
        Text(nameSuffix)
        IconArrowDropDown()
        DropdownMenu(expanded, { expanded = false }) {
            nameSuffixes.forEach { suffix ->
                DropdownMenuItem(text = { Text(suffix) }, onClick = {
                    onNameSuffixChange(suffix)
                    expanded = false
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Birthday(
    backStack: NavBackStack<Route>,
    birthday: LocalDate?,
    setBirthday: (LocalDate?) -> Unit
) {
    ResultEffect<LocalDate>("birthday") {
        setBirthday(it)
    }
    Box {
        OutlinedTextField(
            value = birthday?.formatDisplay() ?: "",
            onValueChange = { },
            readOnly = true,
            label = {Text(stringResource(R.string.birthday))},
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { setBirthday(null) }) {
                    IconRemoveCircle()
                }
            }
        )
        Box(
            modifier = Modifier
                .matchParentSize()
        ) {
            Box(Modifier.fillMaxWidth(0.9f).fillMaxHeight()
                .clickable { backStack.add(Route.EventDatePickerDialog("birthday",birthday)) }){}
        }
    }

    Spacer(Modifier.height(8.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.DateDetailsSection(
    backStack: NavBackStack<Route>,
    details: List<Event>,
    onDetailsChange: (List<Event>) -> Unit,
    icon: @Composable () -> Unit,
    options: List<Int>
) {
    val detailType = stringResource(R.string.dates)
    val context = LocalContext.current
    details.forEachIndexed { index, detail ->
        if(detail.type == CDKEvent.TYPE_BIRTHDAY) return@forEachIndexed
        val isCustom = detail.type == CDKEvent.TYPE_CUSTOM
        Box {
            ResultEffect<LocalDate>(detail.id.toString()) { newDate ->
                onDetailsChange(details.toMutableList().also { list -> list[index] = detail.withValue(newDate.toString()) })
            }
            OutlinedTextField(
                value = detail.startDate.formatDisplay(),
                onValueChange = { },
                readOnly = true,
                label = { Text(detailType) },
                trailingIcon = {
                    Row {
                        var dropdownExpanded by remember { mutableStateOf(false) }
                        TextButton({ dropdownExpanded = true }) {
                            Text(detail.typeString(context))
                            IconArrowDropDown()
                        }
                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false }) {
                            options.forEach { option ->
                                DropdownMenuItem(
                                    onClick = {
                                        onDetailsChange(details.toMutableList().also { it[index] = detail.withType(option) })
                                        dropdownExpanded = false
                                    },
                                    text = { Text(ContactDetail.default<Event>().withType(option).typeString(context)) }
                                )
                            }
                        }
                        IconButton(onClick = {
                            onDetailsChange(details.toMutableList().also { it.removeAt(index) })
                        }) {
                            IconRemoveCircle()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
            ) {
                Box(Modifier.fillMaxWidth(0.6f).fillMaxHeight()
                    .clickable { backStack.add(Route.EventDatePickerDialog(detail.id.toString(),detail.startDate)) }) {}
            }
        }
        if (isCustom) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = detail.label,
                onValueChange = { newLabel ->
                    onDetailsChange(details.toMutableList().also { it[index] = detail.withLabel(newLabel) })
                },
                label = { Text(stringResource(R.string.custom_label)) },
                placeholder = { Text(stringResource(R.string.enter_custom_label)) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(8.dp))
    }
    FilledTonalButton(
        onClick = { onDetailsChange(details + ContactDetail.default<Event>()) },
        modifier = Modifier.fillMaxWidth()
    ) {
        icon()
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.add_date))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.GroupMembershipSection(
    memberGroups: List<ContactGroup>,
    availableGroups: List<ContactGroup>,
    onAddGroup: (Long) -> Unit,
    onRemoveGroup: (Long) -> Unit
) {
    if (memberGroups.isEmpty() && availableGroups.isEmpty()) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        IconGroup(
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.groups),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(8.dp))

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        memberGroups.forEach { group ->
            InputChip(
                selected = false,
                onClick = { onRemoveGroup(group.id) },
                label = { Text(group.name) },
                trailingIcon = {
                    IconRemoveCircle(modifier = Modifier.size(18.dp))
                }
            )
        }
    }

    if (availableGroups.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        var expanded by remember { mutableStateOf(false) }
        Box {
            FilledTonalButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                IconGroup()
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_to_group))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                availableGroups.forEach { group ->
                    DropdownMenuItem(
                        text = { Text(group.name) },
                        onClick = {
                            onAddGroup(group.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AddPictureSection(
    photo: String?,
    viewModel: ContactViewModel,
    onClick: () -> Unit,
    removePhoto: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (photo != null) {
                val bitmap = remember(photo) { viewModel.decodePhoto(photo) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.contact_photo),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            } else {
                IconAddPhoto(
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            val pictureLabel = stringResource(if (photo != null) R.string.change_picture else R.string.add_picture)
            TextButton(onClick) {
                Text(
                    text = pictureLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (photo != null) {
                TextButton(removePhoto) {
                    Text(
                        text = stringResource(R.string.remove_picture),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

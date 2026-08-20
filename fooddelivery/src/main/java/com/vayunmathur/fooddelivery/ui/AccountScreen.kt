@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.fooddelivery.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.fooddelivery.R
import kotlin.uuid.Uuid
import android.content.Context
import android.location.Geocoder
import androidx.core.content.edit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEmojiEvents
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.fooddelivery.api.BitesApi
import com.vayunmathur.fooddelivery.data.AddressStore
import com.vayunmathur.fooddelivery.data.Customer
import com.vayunmathur.fooddelivery.data.CustomerSavings
import com.vayunmathur.fooddelivery.data.PlatformSavings
import com.vayunmathur.fooddelivery.data.Referral
import com.vayunmathur.fooddelivery.data.SavedAddress
import com.vayunmathur.fooddelivery.platform.AppInit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS_NAME = "fooddelivery_prefs"
private const val KEY_TOKEN = "token_json"

@Composable
fun AccountScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var customer by remember { mutableStateOf<Customer?>(null) }
    var savings by remember { mutableStateOf<CustomerSavings?>(null) }
    var referrals by remember { mutableStateOf<List<Referral>>(emptyList()) }
    var platformSavings by remember { mutableStateOf<PlatformSavings?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loggedIn by remember { mutableStateOf(BitesApi.isLoggedIn()) }
    // The saved token is restored by the background warm-up, so "signed out" isn't known to
    // be true until that has landed — don't offer the sign-in card before then.
    var authResolved by remember { mutableStateOf(BitesApi.isLoggedIn()) }

    var stateId by remember { mutableStateOf<String?>(null) }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var authLoading by remember { mutableStateOf(false) }

    var addresses by remember { mutableStateOf<List<SavedAddress>>(emptyList()) }
    var addressesLoaded by remember { mutableStateOf(false) }
    var showAddAddress by remember { mutableStateOf(false) }
    var editingAddress by remember { mutableStateOf<SavedAddress?>(null) }
    var addrLabel by remember { mutableStateOf("") }
    var addrStreet by remember { mutableStateOf("") }
    var addrCity by remember { mutableStateOf("") }
    var addrState by remember { mutableStateOf("") }
    var addrZip by remember { mutableStateOf("") }
    var addrAptUnit by remember { mutableStateOf("") }
    var addrGateCode by remember { mutableStateOf("") }
    var addrInstructions by remember { mutableStateOf("") }
    var savingAddress by remember { mutableStateOf(false) }
    var addrError by remember { mutableStateOf<String?>(null) }
    var triedSubmit by remember { mutableStateOf(false) }

    fun resetAddressForm() {
        addrLabel = ""
        addrStreet = ""
        addrCity = ""
        addrState = ""
        addrZip = ""
        addrAptUnit = ""
        addrGateCode = ""
        addrInstructions = ""
        editingAddress = null
        showAddAddress = false
        addrError = null
        triedSubmit = false
    }

    fun populateForm(addr: SavedAddress) {
        addrLabel = addr.label
        addrStreet = addr.addressStreet
        addrCity = addr.addressCity
        addrState = addr.addressState
        addrZip = addr.addressZip
        addrAptUnit = addr.aptUnit
        addrGateCode = addr.gateCode
        addrInstructions = addr.deliveryInstructions
        editingAddress = addr
        showAddAddress = true
    }

    LaunchedEffect(Unit) {
        addresses = AddressStore.getAll(context)
        addressesLoaded = true
    }

    // The saved token is restored by the background warm-up, so re-read the login state once
    // it has landed instead of assuming the initial (possibly pre-restore) answer.
    LaunchedEffect(Unit) {
        AppInit.awaitReady()
        if (!loggedIn) loggedIn = BitesApi.isLoggedIn()
        authResolved = true
    }

    LaunchedEffect(loggedIn) {
        if (loggedIn) {
            customer = BitesApi.getCustomer()
            savings = BitesApi.getCustomerSavings()
            referrals = BitesApi.getReferrals()
            platformSavings = BitesApi.getPlatformSavings()
        }
        loading = false
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!loggedIn && authResolved) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        IconPerson(modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.sign_in_to_food_delivery),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.view_orders_earn_rewards_and_more),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))

                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text(stringResource(R.string.phone_number)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (codeSent) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = it },
                                label = { Text(stringResource(R.string.verification_code)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        if (authLoading) {
                            CircularProgressIndicator()
                        } else {
                            Button(
                                onClick = {
                                    scope.launch {
                                        authLoading = true
                                        if (!codeSent) {
                                            val sid = BitesApi.verifyPhone(phone)
                                            if (sid != null) {
                                                stateId = sid
                                                codeSent = true
                                            }
                                        } else {
                                            val sid = stateId ?: return@launch
                                            val token = BitesApi.exchangeOtpCodeForToken(sid, code)
                                            if (token != null && token.access_token.isNotEmpty()) {
                                                BitesApi.setToken(token)
                                                loggedIn = true
                                            }
                                        }
                                        authLoading = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (codeSent) "Verify" else "Send Code")
                            }
                        }
                    }
                }
            } else if (loggedIn) {
                if (editingProfile) {
                    customer?.let { c ->
                        EditProfileDialog(
                            customer = c,
                            onDismiss = { editingProfile = false },
                            onSave = { updated ->
                                scope.launch {
                                    editingProfile = false
                                    val saved = BitesApi.createOrUpdateCustomer(updated)
                                    if (saved != null) {
                                        customer = saved
                                        notice = "Profile updated"
                                    } else {
                                        notice = "Couldn't update your profile"
                                    }
                                }
                            },
                        )
                    }
                }

                customer?.let { c ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconPerson(modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(c.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold)
                                    if (c.email.isNotEmpty()) {
                                        Text(c.email,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (c.phone.isNotEmpty()) {
                                        Text(c.phone,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { editingProfile = true }) {
                                    Text(stringResource(R.string.edit))
                                }
                            }
                        }
                    }
                }

                val savingsAmount = savings?.customerSavingsDollars ?: 0.0
                if (savingsAmount > 0) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconEmojiEvents(modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.your_savings),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("$%.2f".format(savingsAmount),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.saved_vs_delivery_apps),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // Email verification — the account's email is unverified until the emailed
                // token is confirmed, so offer to (re)send it.
                customer?.let { c ->
                    if (c.email.isNotEmpty()) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.email_verification),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val ok = BitesApi.sendEmailVerification(c.email)
                                            notice = if (ok) "Verification email sent to ${c.email}"
                                            else "Couldn't send the verification email"
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.send_verification_email)) }
                            }
                        }
                    }
                }

                if (referrals.isNotEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.referrals),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            referrals.forEach { r ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(r.code ?: "#${r.id}", style = MaterialTheme.typography.bodyMedium)
                                    Text(r.status ?: "", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                platformSavings?.let { ps ->
                    if (ps.totalCustomerSavings > 0) {
                        Text(
                            stringResource(R.string.platform_savings, "$%.2f".format(ps.customerSavingsDollars)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                notice?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }

                if (confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text(stringResource(R.string.delete_account)) },
                        text = { Text(stringResource(R.string.delete_account_warning)) },
                        confirmButton = {
                            Button(onClick = {
                                scope.launch {
                                    confirmDelete = false
                                    if (BitesApi.deleteCustomer()) {
                                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                            .edit { remove(KEY_TOKEN) }
                                        BitesApi.clearToken()
                                        loggedIn = false
                                        customer = null
                                        savings = null
                                    } else {
                                        notice = "Couldn't delete the account"
                                    }
                                }
                            }) { Text(stringResource(R.string.delete_account)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDelete = false }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        },
                    )
                }

                HorizontalDivider()

                OutlinedButton(
                    onClick = {
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit { remove(KEY_TOKEN) }
                        BitesApi.clearToken()
                        loggedIn = false
                        customer = null
                        savings = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.sign_out))
                }

                TextButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.delete_account),
                        color = MaterialTheme.colorScheme.error)
                }
            }

            HorizontalDivider()

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.addresses),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                IconButton(onClick = {
                    resetAddressForm()
                    showAddAddress = true
                }) {
                    IconAdd(tint = MaterialTheme.colorScheme.primary)
                }
            }

            if (addressesLoaded && addresses.isEmpty() && !showAddAddress) {
                Text(stringResource(R.string.no_saved_addresses_tap_to_add_one),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            addresses.forEach { addr ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                IconHome(modifier = Modifier.size(20.dp),
                                    tint = if (addr.isDefault) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(addr.label.ifEmpty { "Address" },
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleSmall)
                                        if (addr.isDefault) {
                                            Spacer(Modifier.width(6.dp))
                                            Text(stringResource(R.string.default_address),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    Text(addr.addressStreet,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${addr.addressCity}, ${addr.addressState} ${addr.addressZip}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    AddressStore.delete(context, addr.id)
                                    addresses = AddressStore.getAll(context)
                                }
                            }) {
                                IconDelete(modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!addr.isDefault) {
                                TextButton(onClick = {
                                    scope.launch {
                                        AddressStore.setDefault(context, addr.id)
                                        addresses = AddressStore.getAll(context)
                                    }
                                }) {
                                    Text(stringResource(R.string.set_default), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            TextButton(onClick = { populateForm(addr) }) {
                                Text(stringResource(R.string.edit), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            if (showAddAddress) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (editingAddress != null) "Edit Address" else "Add Address",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedTextField(
                            value = addrLabel,
                            onValueChange = { addrLabel = it },
                            label = { Text(stringResource(R.string.label_e_g_home_work)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = addrStreet,
                            onValueChange = { addrStreet = it; addrError = null },
                            label = { Text(stringResource(R.string.street_address)) },
                            isError = triedSubmit && addrStreet.isBlank(),
                            supportingText = if (triedSubmit && addrStreet.isBlank()) {{ Text(stringResource(R.string.required)) }} else null,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = addrCity,
                                onValueChange = { addrCity = it; addrError = null },
                                label = { Text(stringResource(R.string.city)) },
                                isError = triedSubmit && addrCity.isBlank(),
                                supportingText = if (triedSubmit && addrCity.isBlank()) {{ Text(stringResource(R.string.required)) }} else null,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = addrState,
                                onValueChange = { addrState = it; addrError = null },
                                label = { Text(stringResource(R.string.state)) },
                                isError = triedSubmit && addrState.isBlank(),
                                supportingText = if (triedSubmit && addrState.isBlank()) {{ Text(stringResource(R.string.required)) }} else null,
                                modifier = Modifier.width(80.dp)
                            )
                        }
                        OutlinedTextField(
                            value = addrZip,
                            onValueChange = { addrZip = it; addrError = null },
                            label = { Text(stringResource(R.string.zip_code)) },
                            isError = triedSubmit && addrZip.isBlank(),
                            supportingText = if (triedSubmit && addrZip.isBlank()) {{ Text(stringResource(R.string.required)) }} else null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = addrAptUnit,
                                onValueChange = { addrAptUnit = it },
                                label = { Text(stringResource(R.string.apt_unit)) },
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = addrGateCode,
                                onValueChange = { addrGateCode = it },
                                label = { Text(stringResource(R.string.gate_code)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        OutlinedTextField(
                            value = addrInstructions,
                            onValueChange = { addrInstructions = it },
                            label = { Text(stringResource(R.string.delivery_instructions_optional)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (addrError != null) {
                            Text(addrError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { resetAddressForm() },
                                modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.action_cancel))
                            }
                            Button(
                                onClick = {
                                    triedSubmit = true
                                    if (addrStreet.isBlank() || addrCity.isBlank() ||
                                        addrState.isBlank() || addrZip.isBlank()) return@Button
                                    scope.launch {
                                        savingAddress = true
                                        addrError = null
                                        val fullAddress = "$addrStreet, $addrCity, $addrState $addrZip"
                                        val coords = geocodeAddress(context, fullAddress)
                                        if (coords == null) {
                                            addrError = "Could not verify this address. Check the details and try again."
                                            savingAddress = false
                                            return@launch
                                        }
                                        val addr = SavedAddress(
                                            id = editingAddress?.id ?: Uuid.random().toString(),
                                            label = addrLabel,
                                            addressStreet = addrStreet,
                                            addressCity = addrCity,
                                            addressState = addrState,
                                            addressZip = addrZip,
                                            aptUnit = addrAptUnit,
                                            gateCode = addrGateCode,
                                            deliveryInstructions = addrInstructions,
                                            latitude = coords.first,
                                            longitude = coords.second,
                                            isDefault = editingAddress?.isDefault ?: addresses.isEmpty()
                                        )
                                        AddressStore.save(context, addr)
                                        addresses = AddressStore.getAll(context)
                                        savingAddress = false
                                        resetAddressForm()
                                    }
                                },
                                enabled = !savingAddress,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (savingAddress) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                else Text(stringResource(UiR.string.save))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
private suspend fun geocodeAddress(context: Context, address: String): Pair<Double, Double>? {
    return withContext(Dispatchers.IO) {
        try {
            val results = Geocoder(context).getFromLocationName(address, 1)
            if (!results.isNullOrEmpty()) {
                Pair(results[0].latitude, results[0].longitude)
            } else null
        } catch (_: Exception) { null }
    }
}

/** Edit the name/email on the account; posts the whole customer to POST /customers/me. */
@Composable
private fun EditProfileDialog(
    customer: Customer,
    onDismiss: () -> Unit,
    onSave: (Customer) -> Unit,
) {
    var firstName by remember { mutableStateOf(customer.firstName) }
    var lastName by remember { mutableStateOf(customer.lastName) }
    var email by remember { mutableStateOf(customer.email) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_profile)) },
        text = {
            Column {
                OutlinedTextField(
                    value = firstName,
                    onValueChange = { firstName = it },
                    label = { Text(stringResource(R.string.first_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = lastName,
                    onValueChange = { lastName = it },
                    label = { Text(stringResource(R.string.last_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.email)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(customer.copy(
                    firstName = firstName.trim(),
                    lastName = lastName.trim(),
                    email = email.trim(),
                ))
            }) { Text(stringResource(R.string.save_changes)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

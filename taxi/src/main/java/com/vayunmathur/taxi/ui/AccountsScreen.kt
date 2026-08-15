package com.vayunmathur.taxi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.taxi.R
import com.vayunmathur.taxi.data.AddCardResult
import com.vayunmathur.taxi.data.ChargeAccount
import com.vayunmathur.taxi.data.NewCard
import com.vayunmathur.taxi.data.PaymentActionResult
import com.vayunmathur.taxi.data.PaymentMethodsResult
import com.vayunmathur.taxi.network.lyft.LyftProvider
import com.vayunmathur.taxi.data.lyft.LyftTokenStore
import java.util.Calendar
import kotlinx.coroutines.launch

@Composable
fun AccountsScreen(onConnectLyft: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val provider = remember { LyftProvider(context.applicationContext) }

    var lyftSignedIn by remember { mutableStateOf(false) }
    var loadingPayments by remember { mutableStateOf(false) }
    var accounts by remember { mutableStateOf<List<ChargeAccount>>(emptyList()) }
    var paymentError by remember { mutableStateOf<String?>(null) }
    // The id of the account a live set-default/remove is currently running against.
    var busyId by remember { mutableStateOf<String?>(null) }
    // Add-card dialog state.
    var showAddCard by remember { mutableStateOf(false) }
    var addingCard by remember { mutableStateOf(false) }
    var addCardError by remember { mutableStateOf<String?>(null) }
    var showSignOut by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }

    suspend fun loadPayments() {
        loadingPayments = true
        paymentError = null
        when (val result = provider.paymentMethods()) {
            is PaymentMethodsResult.Success -> accounts = result.accounts
            is PaymentMethodsResult.NotSignedIn -> {
                accounts = emptyList()
                lyftSignedIn = false
            }
            is PaymentMethodsResult.Failed -> paymentError = result.message
            PaymentMethodsResult.Unsupported -> paymentError = null
        }
        loadingPayments = false
    }

    LaunchedEffect(Unit) {
        lyftSignedIn = LyftTokenStore(context.applicationContext).isSignedIn()
        if (lyftSignedIn) loadPayments()
    }

    suspend fun applyAction(result: PaymentActionResult, onOk: suspend () -> Unit) {
        when (result) {
            is PaymentActionResult.Success ->
                if (result.accounts != null) accounts = result.accounts else onOk()
            is PaymentActionResult.Failed -> paymentError = result.message
            PaymentActionResult.Unsupported -> Unit
        }
        busyId = null
    }

    AppScaffold(title = stringResource(R.string.nav_settings)) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.provider_lyft)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (lyftSignedIn) R.string.signed_in else R.string.not_signed_in,
                            ),
                        )
                    },
                    modifier = Modifier.clickable(onClick = onConnectLyft),
                )
            }

            if (lyftSignedIn) {
                TextButton(
                    onClick = { showSignOut = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error)
                }
            }

            Text(
                stringResource(R.string.payment_methods),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                !lyftSignedIn -> InfoCard(stringResource(R.string.payment_sign_in))
                loadingPayments -> LoadingCard(stringResource(R.string.payment_methods_loading))
                paymentError != null -> InfoCard(
                    stringResource(R.string.payment_methods_error, paymentError.orEmpty()),
                )
                accounts.isEmpty() -> InfoCard(stringResource(R.string.payment_methods_none))
                else -> Card {
                    accounts.forEachIndexed { index, account ->
                        PaymentRow(
                            account = account,
                            busy = busyId == account.id,
                            onSetDefault = {
                                busyId = account.id
                                scope.launch {
                                    applyAction(provider.setDefaultPaymentMethod(account.id)) {
                                        loadPayments()
                                    }
                                }
                            },
                            onRemove = {
                                busyId = account.id
                                scope.launch {
                                    applyAction(provider.removePaymentMethod(account.id)) {
                                        loadPayments()
                                    }
                                }
                            },
                        )
                        if (index < accounts.lastIndex) HorizontalDivider()
                    }
                }
            }

            if (lyftSignedIn && !loadingPayments) {
                Button(
                    onClick = {
                        addCardError = null
                        showAddCard = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.add_card))
                }
            }
        }
    }

    if (showSignOut) {
        AlertDialog(
            onDismissRequest = { if (!signingOut) showSignOut = false },
            title = { Text(stringResource(R.string.sign_out_title)) },
            text = { Text(stringResource(R.string.sign_out_message)) },
            confirmButton = {
                TextButton(
                    enabled = !signingOut,
                    onClick = {
                        signingOut = true
                        scope.launch {
                            provider.signOut()
                            signingOut = false
                            showSignOut = false
                            lyftSignedIn = false
                            accounts = emptyList()
                            paymentError = null
                        }
                    },
                ) {
                    Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(enabled = !signingOut, onClick = { showSignOut = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showAddCard) {
        AddCardDialog(
            adding = addingCard,
            error = addCardError,
            onDismiss = {
                if (!addingCard) {
                    showAddCard = false
                    addCardError = null
                }
            },
            onSubmit = { card, makeDefault ->
                addingCard = true
                addCardError = null
                scope.launch {
                    when (val result = provider.addCard(card, makeDefault)) {
                        is AddCardResult.Success -> {
                            if (result.accounts != null) accounts = result.accounts else loadPayments()
                            addingCard = false
                            showAddCard = false
                        }
                        is AddCardResult.Failed -> {
                            addCardError = result.message
                            addingCard = false
                        }
                        AddCardResult.Unsupported -> {
                            addCardError = context.getString(R.string.add_card_unsupported)
                            addingCard = false
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun PaymentRow(
    account: ChargeAccount,
    busy: Boolean,
    onSetDefault: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(account.label, style = MaterialTheme.typography.bodyLarge)
            if (account.isDefault) {
                Text(
                    stringResource(R.string.payment_default),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            if (!account.isDefault) {
                TextButton(onClick = onSetDefault) {
                    Text(stringResource(R.string.payment_set_default))
                }
            }
            TextButton(onClick = onRemove) {
                Text(
                    stringResource(R.string.payment_remove),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun InfoCard(message: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(
            message,
            Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadingCard(message: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddCardDialog(
    adding: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (NewCard, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var number by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var cvc by remember { mutableStateOf("") }
    var zip by remember { mutableStateOf("") }
    var makeDefault by remember { mutableStateOf(false) }
    // Client-side validation error, shown until cleared by a successful re-validate; the
    // processor/Lyft [error] is surfaced verbatim underneath.
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_card_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text(stringResource(R.string.card_number)) },
                    singleLine = true,
                    enabled = !adding,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = expiry,
                        onValueChange = { expiry = it },
                        label = { Text(stringResource(R.string.card_expiry)) },
                        singleLine = true,
                        enabled = !adding,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = cvc,
                        onValueChange = { cvc = it },
                        label = { Text(stringResource(R.string.card_cvc)) },
                        singleLine = true,
                        enabled = !adding,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = zip,
                    onValueChange = { zip = it },
                    label = { Text(stringResource(R.string.card_zip)) },
                    singleLine = true,
                    enabled = !adding,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = makeDefault,
                        onCheckedChange = { makeDefault = it },
                        enabled = !adding,
                    )
                    Text(stringResource(R.string.card_set_default))
                }
                val shownError = validationError ?: error
                if (shownError != null) {
                    Text(
                        shownError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (adding) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.adding_card))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !adding,
                onClick = {
                    validateCard(context, number, expiry, cvc, zip).fold(
                        onSuccess = {
                            validationError = null
                            onSubmit(it, makeDefault)
                        },
                        onFailure = { validationError = it.message },
                    )
                },
            ) { Text(stringResource(R.string.add)) }
        },
        dismissButton = {
            TextButton(enabled = !adding, onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * Validates the typed card fully client-side so an invalid card never reaches a live processor
 * call. Returns a [NewCard] with normalised (digits-only) number/cvc, or a failure whose message
 * is a user-facing string.
 */
private fun validateCard(
    context: android.content.Context,
    number: String,
    expiry: String,
    cvc: String,
    zip: String,
): Result<NewCard> {
    val digits = number.filter { it.isDigit() }
    if (digits.length !in 13..19 || !luhnValid(digits)) {
        return Result.failure(IllegalArgumentException(context.getString(R.string.card_invalid_number)))
    }
    val parsed = parseExpiry(expiry)
        ?: return Result.failure(IllegalArgumentException(context.getString(R.string.card_invalid_expiry)))
    val (month, year) = parsed
    val now = Calendar.getInstance()
    val curYear = now.get(Calendar.YEAR)
    val curMonth = now.get(Calendar.MONTH) + 1
    if (year < curYear || (year == curYear && month < curMonth)) {
        return Result.failure(IllegalArgumentException(context.getString(R.string.card_expired)))
    }
    val cvcDigits = cvc.filter { it.isDigit() }
    if (cvcDigits.length !in 3..4) {
        return Result.failure(IllegalArgumentException(context.getString(R.string.card_invalid_cvc)))
    }
    return Result.success(NewCard(digits, month, year, cvcDigits, zip.trim()))
}

/** Parses `MM/YY` (or `MMYY`) into (month 1-12, full year). Null when malformed. */
private fun parseExpiry(raw: String): Pair<Int, Int>? {
    val digits = raw.filter { it.isDigit() }
    if (digits.length != 4) return null
    val month = digits.substring(0, 2).toIntOrNull() ?: return null
    val yy = digits.substring(2, 4).toIntOrNull() ?: return null
    if (month !in 1..12) return null
    return month to (2000 + yy)
}

private fun luhnValid(digits: String): Boolean {
    var sum = 0
    var alt = false
    for (i in digits.indices.reversed()) {
        var d = digits[i] - '0'
        if (alt) {
            d *= 2
            if (d > 9) d -= 9
        }
        sum += d
        alt = !alt
    }
    return sum % 10 == 0
}

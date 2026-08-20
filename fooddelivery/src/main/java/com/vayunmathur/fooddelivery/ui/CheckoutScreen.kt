package com.vayunmathur.fooddelivery.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.fooddelivery.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stripe.android.PaymentConfiguration
import com.stripe.android.Stripe
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.fooddelivery.api.BitesApi
import com.vayunmathur.fooddelivery.BuildConfig
import com.vayunmathur.fooddelivery.data.AddressStore
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.CheckoutAddress
import com.vayunmathur.fooddelivery.data.CheckoutCartItem
import com.vayunmathur.fooddelivery.data.CheckoutRequest
import com.vayunmathur.fooddelivery.data.Customer
import com.vayunmathur.fooddelivery.data.Deal
import com.vayunmathur.fooddelivery.data.OrderRewards
import com.vayunmathur.fooddelivery.data.SavedAddress
import com.vayunmathur.fooddelivery.data.CheckoutResponse
import com.vayunmathur.fooddelivery.notifications.OrderTrackingService
import com.vayunmathur.fooddelivery.platform.AppInit
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun CheckoutScreen(
    items: List<CartItem>,
    onBack: () -> Unit,
    onOrderPlaced: () -> Unit,
) {
    val context = LocalContext.current

    var isPickup by remember { mutableStateOf(false) }
    var tipCents by remember { mutableIntStateOf(300) }
    var deliveryInstructions by remember { mutableStateOf("") }
    var paying by remember { mutableStateOf(false) }
    var fetchingPrices by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var orderSuccess by remember { mutableStateOf(false) }
    var checkoutResponse by remember { mutableStateOf<CheckoutResponse?>(null) }

    var promoCode by remember { mutableStateOf("") }
    var customer by remember { mutableStateOf<Customer?>(null) }
    /** Reward credit applied to this order, per GET /orders/{uuid}/rewards. */
    var rewards by remember { mutableStateOf<OrderRewards?>(null) }
    var deals by remember { mutableStateOf<List<Deal>>(emptyList()) }
    /** Order created by the last successful checkout call; re-priced in place, not re-created. */
    var lastOrderUuid by remember { mutableStateOf<String?>(null) }
    var selectedDealId by remember { mutableStateOf<Int?>(null) }

    // One prefs read plus one JSON decode, off the main thread — the default address is
    // picked out of the list already in hand rather than re-reading the store for it.
    var addresses by remember { mutableStateOf<List<SavedAddress>>(emptyList()) }
    var addressesLoaded by remember { mutableStateOf(false) }
    var selectedAddress by remember { mutableStateOf<SavedAddress?>(null) }

    LaunchedEffect(Unit) {
        val all = AddressStore.getAll(context)
        val default = all.firstOrNull { it.isDefault } ?: all.firstOrNull()
        addresses = all
        selectedAddress = default
        // Don't overwrite anything typed while the read was in flight.
        if (deliveryInstructions.isEmpty()) {
            deliveryInstructions = default?.deliveryInstructions ?: ""
        }
        addressesLoaded = true
    }

    // The reference sends the customer's identity with every checkout.
    LaunchedEffect(Unit) {
        AppInit.awaitReady()
        customer = BitesApi.getCustomer()
    }

    val subtotalCents = items.sumOf {
        (it.menuItem.price + it.selectedModifiers.sumOf { m -> m.price * m.quantity }) * it.quantity
    }
    val subtotal = subtotalCents / 100.0

    val confirmedOrder = checkoutResponse?.order
    /** Reference formula: component sum minus the reward credit applied to this order. */
    val rewardsApplied = (rewards?.rewardsAvailable ?: 0) / 100.0
    val payTotal = confirmedOrder?.let { it.componentTotal - rewardsApplied }
    val merchantId = items.firstOrNull()?.merchantId ?: 0
    val canFetch = items.isNotEmpty() && (isPickup || selectedAddress != null)

    // Deals the merchant currently has running; picking one sends its dealId with checkout.
    LaunchedEffect(merchantId) {
        AppInit.awaitReady()
        deals = if (merchantId != 0) BitesApi.getActiveDealsByMerchant(merchantId) else emptyList()
    }

    LaunchedEffect(isPickup, tipCents, selectedAddress?.id, promoCode, customer?.uuid, selectedDealId) {
        if (!canFetch) return@LaunchedEffect
        AppInit.awaitReady()
        checkoutResponse = null
        error = null
        fetchingPrices = true
        delay(400)
        // Modifiers already carry their group id, price and quantity from selection time,
        // so they go over the wire exactly as the reference cart stores them.
        val cartItems = items.map { item ->
            CheckoutCartItem(
                itemId = item.menuItem.id,
                quantity = item.quantity,
                specialInstructions = item.specialInstructions,
                modifiers = item.selectedModifiers,
            )
        }
        val addr = if (!isPickup) selectedAddress?.let { a ->
            CheckoutAddress(
                addressStreet = a.addressStreet,
                addressCity = a.addressCity,
                addressState = a.addressState,
                addressZip = a.addressZip,
                addressUnit = a.aptUnit,
                latitude = a.latitude,
                longitude = a.longitude,
            )
        } else null
        val request = CheckoutRequest(
            cartItems = cartItems,
            address = addr,
            isPickup = isPickup,
            tips = tipCents,
            promoCode = promoCode.trim().ifBlank { null },
            dealId = selectedDealId,
            deliveryInstructions = deliveryInstructions.ifBlank { null },
            gateCode = selectedAddress?.gateCode?.ifBlank { null },
            uuid = lastOrderUuid,
            firstName = customer?.firstName?.ifBlank { null },
            lastName = customer?.lastName?.ifBlank { null },
            email = customer?.email?.ifBlank { null },
            phone = customer?.phone?.ifBlank { null },
        )
        val response = BitesApi.checkout(merchantId, request)
        if (BuildConfig.DEV_BUILD) {
            Log.d("Checkout", "response.order=${response?.order}")
            Log.d("Checkout", "response.clientSecret=${response?.clientSecret?.take(20)}")
            Log.d("Checkout", "response.serviceable=${response?.serviceable}")
            response?.order?.let { o ->
                Log.d("Checkout", "order: foodTotal=${o.foodTotal} taxes=${o.taxes} deliveryFee=${o.deliveryFee} fees=${o.fees} tips=${o.tips} displayTotal=${o.displayTotal}")
            }
        }
        // Reuse the draft order on the next re-price; drop it if this call failed so we
        // don't keep asking the server to update an order it can't find.
        lastOrderUuid = response?.order?.uuid
        if (response == null) {
            error = "Failed to load pricing. Please try again."
        } else if (!response.isServiceable) {
            error = "This address is not serviceable for delivery."
        } else {
            checkoutResponse = response
        }
        fetchingPrices = false
    }

    // The screen showed foodTotal+fees+taxes+deliveryFee+tips, which is the total *before*
    // any reward credit — the reference fetches GET /orders/{uuid}/rewards and subtracts
    // `rewardsAvailable` from that same sum before displaying it, which is why a discounted
    // order read higher on screen than Stripe charged.
    // The reference gates this on context.customer being present (:1255352) — it's a
    // signed-in check, not a feature flag, so mirror it rather than always fetching.
    LaunchedEffect(confirmedOrder?.uuid, customer?.uuid) {
        val orderUuid = confirmedOrder?.uuid?.takeIf { it.isNotBlank() }
        rewards = if (customer == null || orderUuid == null) null
        else BitesApi.getOrderRewards(orderUuid)
        if (BuildConfig.DEV_BUILD) {
            Log.d("Checkout", "rewardsAvailable=${rewards?.rewardsAvailable} rate=${rewards?.rewardsRate}")
        }
    }

    // Cross-check against what Stripe will really charge; UI follows the reference formula,
    // but log loudly if the PaymentIntent disagrees so any remaining gap is visible.
    LaunchedEffect(checkoutResponse?.clientSecret, payTotal) {
        val secret = checkoutResponse?.clientSecret?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val amount = withContext(Dispatchers.IO) {
            runCatching {
                Stripe(context, PaymentConfiguration.getInstance(context).publishableKey)
                    .retrievePaymentIntentSynchronous(secret).amount?.toInt()
            }.onFailure { Log.w("Checkout", "PaymentIntent lookup failed", it) }.getOrNull()
        } ?: return@LaunchedEffect
        val shown = payTotal ?: return@LaunchedEffect
        if (kotlin.math.abs(amount / 100.0 - shown) > 0.005 && BuildConfig.DEV_BUILD) {
            Log.w("Checkout", "MISMATCH: stripe=${amount / 100.0} shown=$shown " +
                "componentTotal=${confirmedOrder.componentTotal} rewards=${rewards?.rewardsAvailable}")
        }
    }

    val paymentSheet = rememberPaymentSheet { result ->
        when (result) {
            is PaymentSheetResult.Completed -> {
                orderSuccess = true
                OrderTrackingService.start(context, checkoutResponse?.order?.id)
                onOrderPlaced()
            }
            is PaymentSheetResult.Canceled -> {
                paying = false
            }
            is PaymentSheetResult.Failed -> {
                error = result.error.localizedMessage ?: "Payment failed"
                paying = false
            }
        }
    }

    if (orderSuccess) {
        AppScaffold(
            title = stringResource(R.string.order_confirmed),
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                IconCheck(modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.order_placed), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.your_order_is_being_prepared), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    AppScaffold(
        title = stringResource(R.string.checkout),
        onNavigateBack = onBack,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                item {
                    Text(stringResource(R.string.order_summary), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                }

                itemsIndexed(items, key = { _, item -> item.lineId }) { _, item ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("${item.quantity}x ${item.menuItem.name}",
                                style = MaterialTheme.typography.bodyMedium)
                            if (item.selectedModifiers.isNotEmpty()) {
                                Text(item.selectedModifiers.joinToString(", ") { it.name },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text("$%.2f".format(item.totalPrice), style = MaterialTheme.typography.bodyMedium)
                    }
                }

                item {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    Text(stringResource(R.string.order_type), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !isPickup,
                            onClick = { isPickup = false },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                            label = { Text(stringResource(R.string.delivery)) }
                        )
                        SegmentedButton(
                            selected = isPickup,
                            onClick = { isPickup = true },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                            label = { Text(stringResource(R.string.pickup)) }
                        )
                    }
                }

                if (!isPickup) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.delivery_address), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))

                        if (addresses.isEmpty()) {
                            if (addressesLoaded) {
                                Text(stringResource(R.string.no_saved_addresses_add_one_in_account_se),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            addresses.forEach { addr ->
                                val isSelected = selectedAddress?.id == addr.id
                                Card(
                                    onClick = {
                                        selectedAddress = addr
                                        if (addr.deliveryInstructions.isNotEmpty()) {
                                            deliveryInstructions = addr.deliveryInstructions
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        IconLocationOn(
                                            modifier = Modifier.size(20.dp),
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(addr.label.ifEmpty { "Address" },
                                                fontWeight = FontWeight.Medium,
                                                style = MaterialTheme.typography.bodyMedium)
                                            Text(addr.addressStreet,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            val cityStateZip = listOfNotNull(
                                                addr.addressCity.ifEmpty { null },
                                                addr.addressState.ifEmpty { null },
                                                addr.addressZip.ifEmpty { null },
                                            ).joinToString(", ")
                                            if (cityStateZip.isNotEmpty()) {
                                                Text(cityStateZip,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            if (addr.aptUnit.isNotEmpty()) {
                                                Text(stringResource(R.string.apt_unit_2, addr.aptUnit),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            if (addr.gateCode.isNotEmpty()) {
                                                Text(stringResource(R.string.gate, addr.gateCode),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        if (isSelected) {
                                            IconCheck(modifier = Modifier.size(20.dp),
                                                tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = deliveryInstructions,
                            onValueChange = { deliveryInstructions = it },
                            label = { Text(stringResource(R.string.delivery_instructions_optional)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    Text(stringResource(R.string.tip), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 200, 300, 500).forEach { cents ->
                            val label = if (cents == 0) "None" else "$%.2f".format(cents / 100.0)
                            FilterChip(
                                selected = tipCents == cents,
                                onClick = { tipCents = cents },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    if (deals.isNotEmpty()) {
                        Text(stringResource(R.string.deals), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        deals.forEach { deal ->
                            val chosen = selectedDealId == deal.id
                            Card(
                                // Tapping a chosen deal clears it, so a deal can be removed.
                                onClick = { selectedDealId = if (chosen) null else deal.id },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(deal.title, fontWeight = FontWeight.Medium,
                                            style = MaterialTheme.typography.bodyMedium)
                                        if (deal.description.isNotEmpty()) {
                                            Text(deal.description, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    if (chosen) {
                                        IconCheck(modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    Text(stringResource(R.string.promo_code), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = promoCode,
                        onValueChange = { promoCode = it },
                        label = { Text(stringResource(R.string.promo_code_optional)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    PriceRow("Subtotal", subtotal)
                    if (fetchingPrices) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.calculating_tax_fees),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (confirmedOrder != null) {
                        PriceRow("Tax", confirmedOrder.taxesDollars)
                        if (confirmedOrder.deliveryFee > 0) PriceRow("Delivery fee", confirmedOrder.deliveryFeeDollars)
                        if (confirmedOrder.fees != null && confirmedOrder.fees > 0) PriceRow("Service fees", confirmedOrder.fees / 100.0)
                        if (confirmedOrder.tips > 0) PriceRow("Tip", confirmedOrder.tipsDollars)
                        // Whatever the charge nets out below the component sum is a discount
                        // (rewards / deal / promo / referral) — show it instead of silently
                        // letting the total disagree with the components above it.
                        if (rewardsApplied > 0.005) PriceRow("Rewards", -rewardsApplied)
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.total), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("$%.2f".format(payTotal ?: confirmedOrder.displayTotal), fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }

                if (error != null) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(error!!, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Button(
                        onClick = {
                            paying = true
                            paymentSheet.presentWithPaymentIntent(
                                checkoutResponse!!.clientSecret,
                                PaymentSheet.Configuration(
                                    merchantDisplayName = items.firstOrNull()?.merchantName ?: "Food Delivery",
                                )
                            )
                        },
                        enabled = checkoutResponse != null && !paying && !fetchingPrices,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (paying || fetchingPrices) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            if (payTotal != null) {
                                Text("Place Order · $%.2f".format(payTotal))
                            } else {
                                Text(stringResource(R.string.place_order))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PriceRow(label: String, amount: Double) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$%.2f".format(amount), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

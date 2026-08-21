package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.util.BookingState
import com.vayunmathur.travel.util.TravelViewModel
import androidx.compose.ui.res.stringResource

/**
 * Payment screen.
 *
 * **Sandbox (shipped):** books the offer with Duffel's test `balance` payment —
 * no card entry, no PCI surface — via [TravelViewModel.createOrder].
 *
 * **Live cards (deferred):** this same screen is where a Duffel Payments hosted
 * card component would be rendered in a `WebView` (using a client key minted by
 * `/api/travel/payment-key`), then the resulting card token passed to
 * `createOrder`. The flow around it (review → passengers → confirmation) does
 * not change, so that layers in here without reworking navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.Payment,
) {
    val review by viewModel.review.collectAsStateWithLifecycle()
    val booking by viewModel.booking.collectAsStateWithLifecycle()
    val customers by viewModel.customers.collectAsStateWithLifecycle()
    val activeCustomerId by viewModel.activeCustomerId.collectAsStateWithLifecycle()
    val activeCustomer = customers.firstOrNull { it.id == activeCustomerId }

    LaunchedEffect(booking) {
        val b = booking
        if (b is BookingState.Success) {
            viewModel.resetBooking()
            // Replace the whole booking flow with the confirmation so Back
            // returns to Home rather than the payment screen.
            backStack.reset(Route.Home, Route.Confirmation(b.result.orderId))
        }
    }

    AppScaffold(
        title = stringResource(R.string.payment),
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        val offer = review.offer
        val loading = booking is BookingState.Loading
        val allowHold = offer != null && !offer.requiresInstantPayment
        var hold by remember { mutableStateOf(false) }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.total_due), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (offer != null) formatMoney(offer.totalAmount, offer.currency) else "—",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (activeCustomer != null) {
                Text(
                    stringResource(R.string.booking_for, activeCustomer.displayName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (allowHold) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    com.vayunmathur.library.ui.FilterChip(
                        selected = !hold,
                        onClick = { hold = false },
                        label = { Text(stringResource(R.string.pay_now)) },
                    )
                    com.vayunmathur.library.ui.FilterChip(
                        selected = hold,
                        onClick = { hold = true },
                        label = { Text(stringResource(R.string.hold_pay_later)) },
                    )
                }
            }

            Text(
                if (hold) {
                    stringResource(R.string.hold_order_the_price_is_held_and_you_pay)
                } else {
                    stringResource(R.string.sandbox_booking_paid_with_a_duffel_test)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            (booking as? BookingState.Error)?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = { viewModel.createOrder(hold = hold) },
                enabled = offer != null && !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.booking))
                } else {
                    Text(if (hold) stringResource(R.string.place_hold) else stringResource(R.string.pay_with_test_balance))
                }
            }
        }
    }
}

package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconCheckCircle
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.util.TravelViewModel
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmationPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.Confirmation,
) {
    val trips by viewModel.bookedTrips.collectAsStateWithLifecycle()
    val trip = trips.find { it.orderId == route.orderId }
    val paymentAction by viewModel.payment.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(paymentAction) {
        if (paymentAction is com.vayunmathur.travel.util.PaymentActionState.Success) {
            viewModel.resetPaymentAction()
        }
    }

    DetailScaffold(
        title = if (trip?.awaitingPayment == true) stringResource(R.string.on_hold) else stringResource(R.string.booking_confirmed),
        scrollBehavior = appBarScrollBehavior(),
    ) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (trip == null) {
                Text(stringResource(R.string.trip_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { backStack.reset(Route.Home) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.back_to_search))
                }
                return@Column
            }

            IconCheckCircle(
                modifier = Modifier.size(64.dp).padding(top = 8.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(if (trip.awaitingPayment) stringResource(R.string.held_pay_to_confirm) else stringResource(R.string.you_re_booked), style = MaterialTheme.typography.headlineSmall)

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailRow("Booking reference", trip.bookingReference, emphasize = true)
                    HorizontalDivider()
                    DetailRow("Route", trip.route)
                    DetailRow("Departs", TravelViewModel.prettyDate(trip.departDate))
                    DetailRow(if (trip.awaitingPayment) "Amount due" else "Amount paid", formatMoney(trip.amount, trip.currency))
                    DetailRow("Status", trip.status.replaceFirstChar(Char::uppercase))
                    if (trip.awaitingPayment && trip.paymentRequiredBy.isNotBlank()) {
                        DetailRow("Pay before", TravelViewModel.prettyDate(trip.paymentRequiredBy.take(10)))
                    }
                }
            }

            if (trip.awaitingPayment) {
                val paying = paymentAction is com.vayunmathur.travel.util.PaymentActionState.Loading
                (paymentAction as? com.vayunmathur.travel.util.PaymentActionState.Error)?.let {
                    Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = { viewModel.payOrder(trip.orderId) },
                    enabled = !paying,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (paying) stringResource(R.string.paying) else stringResource(R.string.pay_now_with_test_balance)) }
            }

            Button(onClick = { backStack.reset(Route.Home) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(UiR.string.done))
            }
            OutlinedButton(
                onClick = { backStack.add(Route.OrderDetail(trip.orderId)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.view_full_itinerary)) }
            OutlinedButton(
                onClick = { backStack.reset(Route.Home, Route.Trips) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.view_my_trips)) }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, emphasize: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

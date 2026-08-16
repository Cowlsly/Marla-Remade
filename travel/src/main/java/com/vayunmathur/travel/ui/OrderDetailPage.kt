package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.rememberIs24Hour
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.network.OrderDetailDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.util.OrderDetailActions
import com.vayunmathur.travel.util.OrderDetailUiState
import com.vayunmathur.travel.util.PaymentActionState
import com.vayunmathur.travel.util.TravelViewModel
import androidx.compose.ui.res.stringResource

/** Binds [TravelViewModel] and the back stack to the stateless [OrderDetailScreen]. */
@Composable
fun OrderDetailPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.OrderDetail,
) {
    val state by viewModel.orderDetail.collectAsStateWithLifecycle()
    val paymentAction by viewModel.payment.collectAsStateWithLifecycle()
    val orderEvents by viewModel.orderEvents.collectAsStateWithLifecycle()
    val events = orderEvents[route.orderId].orEmpty()

    LaunchedEffect(route.orderId) {
        viewModel.loadOrderDetail(route.orderId)
        viewModel.loadOrderEvents(route.orderId)
    }
    LaunchedEffect(paymentAction) {
        if (paymentAction is PaymentActionState.Success) {
            viewModel.resetPaymentAction()
            viewModel.loadOrderDetail(route.orderId)
        }
    }

    val actions = remember(viewModel, backStack) {
        object : OrderDetailActions {
            override fun payOrder(orderId: String) = viewModel.payOrder(orderId)
            override fun openChange(orderId: String) = backStack.add(Route.Change(orderId))
            override fun openCancel(orderId: String) = backStack.add(Route.Cancel(orderId))
            override fun back() = backStack.pop()
        }
    }

    OrderDetailScreen(
        state = OrderDetailUiState(
            loading = state.loading,
            error = state.error,
            order = state.order,
            events = events,
            payment = paymentAction,
        ),
        actions = actions,
    )
}

/**
 * A booked order, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailScreen(state: OrderDetailUiState, actions: OrderDetailActions) {
    DetailScaffold(
        title = stringResource(R.string.order),
        onNavigateBack = { actions.back() },
    ) {
        val order = state.order
        if (order == null) {
            StatusBox(
                loading = state.loading,
                error = state.error,
                isEmpty = !state.loading && state.error == null,
                emptyMessage = "Order not found.",
            )
            return@DetailScaffold
        }
        state.events.forEach { event -> OrderEventBanner(event.message) }

        ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OrderRow("Booking reference", order.bookingReference, emphasize = true)
                    HorizontalDivider()
                    OrderRow("Status", order.status.replaceFirstChar(Char::uppercase))
                    OrderRow(
                        "Payment",
                        if (order.paymentStatus == "awaiting_payment") "Awaiting payment" else "Paid",
                    )
                    OrderRow("Total", formatMoney(order.totalAmount, order.currency))
                    if (order.passengerNames.isNotEmpty()) {
                        OrderRow("Passengers", order.passengerNames.joinToString(", "))
                    }
                }
            }

            order.slices.forEachIndexed { index, slice ->
                OrderSliceCard(
                    title = if (order.slices.size > 1) "Flight ${index + 1}" else "Itinerary",
                    slice = slice,
                )
            }

            if (order.paymentStatus == "awaiting_payment" && order.status != "cancelled") {
                val paying = state.payment is PaymentActionState.Loading
                (state.payment as? PaymentActionState.Error)?.let {
                    Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = { actions.payOrder(order.orderId) },
                    enabled = !paying,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (paying) stringResource(R.string.paying) else stringResource(R.string.pay_now_with_test_balance)) }
            }

            if (order.status != "cancelled") {
                OutlinedButton(
                    onClick = { actions.openChange(order.orderId) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.change_flights)) }
                OutlinedButton(
                    onClick = { actions.openCancel(order.orderId) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.cancel_order)) }
            }
    }
}

@Composable
private fun OrderEventBanner(message: String) {
    com.vayunmathur.library.ui.Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun OrderSliceCard(title: String, slice: SliceDto) {
    val is24 = rememberIs24Hour()
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            slice.segments.forEach { seg ->
                Text(
                    "${seg.origin} ${formatTime(seg.departureAt, is24)}  →  ${seg.destination} ${formatTime(seg.arrivalAt, is24)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val carrier = listOf(seg.carrier, seg.flightNumber).filter { it.isNotBlank() }.joinToString(" ")
                if (carrier.isNotBlank()) {
                    Text(
                        carrier,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun OrderRow(label: String, value: String, emphasize: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

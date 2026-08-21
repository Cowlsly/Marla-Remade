package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconFlight
import com.vayunmathur.library.ui.IconHotel
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.data.BookedTrip
import com.vayunmathur.travel.network.OrderDetailDto
import com.vayunmathur.travel.util.TravelViewModel
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsPage(backStack: NavBackStack<Route>, viewModel: TravelViewModel) {
    val trips by viewModel.bookedTrips.collectAsStateWithLifecycle()
    val remote by viewModel.remoteOrders.collectAsStateWithLifecycle()
    val orderEvents by viewModel.orderEvents.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(remote.orders) {
        remote.orders.forEach { viewModel.loadOrderEvents(it.orderId) }
    }

    AppScaffold(
        title = stringResource(R.string.my_trips),
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            item {
                OutlinedButton(
                    onClick = { viewModel.loadRemoteOrders() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) { Text(if (remote.loading) stringResource(R.string.syncing) else stringResource(R.string.sync_remote_orders)) }
            }

            if (trips.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.saved)) }
                items(trips) { trip -> LocalTripCard(trip) { backStack.add(destinationForTrip(trip)) } }
            }

            if (remote.orders.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.synced_from_duffel)) }
                items(remote.orders) { order ->
                    RemoteOrderCard(order, hasUpdates = orderEvents[order.orderId].orEmpty().isNotEmpty()) {
                        backStack.add(Route.OrderDetail(order.orderId))
                    }
                }
            }

            if (remote.error != null) {
                item {
                    Text(
                        remote.error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            if (trips.isEmpty() && remote.orders.isEmpty() && !remote.loading) {
                item {
                    StatusBox(
                        loading = false,
                        error = null,
                        isEmpty = true,
                        emptyMessage = "No booked trips yet.",
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalTripCard(trip: BookedTrip, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { onClick() },
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (trip.type == "stay") {
                IconHotel(modifier = Modifier.padding(end = 12.dp))
            } else {
                IconFlight(modifier = Modifier.padding(end = 12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(trip.route, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${TravelViewModel.prettyDate(trip.departDate)} · ${trip.bookingReference}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (trip.awaitingPayment) {
                    AssistChip(onClick = onClick, label = { Text(stringResource(R.string.payment_due)) })
                } else if (trip.status.equals("cancelled", ignoreCase = true)) {
                    AssistChip(onClick = onClick, label = { Text(stringResource(R.string.cancelled)) })
                }
            }
            Text(
                formatMoney(trip.amount, trip.currency),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RemoteOrderCard(order: OrderDetailDto, hasUpdates: Boolean = false, onClick: () -> Unit) {
    val route = order.slices.firstOrNull()?.let { "${it.origin} → ${it.destination}" } ?: order.bookingReference
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { onClick() },
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconFlight(modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(route, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${order.bookingReference} · ${order.status.replaceFirstChar(Char::uppercase)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasUpdates) {
                    AssistChip(onClick = onClick, label = { Text(stringResource(R.string.schedule_change)) })
                } else if (order.paymentStatus == "awaiting_payment") {
                    AssistChip(onClick = onClick, label = { Text(stringResource(R.string.payment_due)) })
                }
            }
            Text(
                formatMoney(order.totalAmount, order.currency),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Where tapping a local trip goes, depending on flight vs stay. */
private fun destinationForTrip(trip: BookedTrip): Route =
    if (trip.type == "stay") Route.StayConfirmation(trip.orderId) else Route.OrderDetail(trip.orderId)

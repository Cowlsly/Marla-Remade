package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconCheckCircle
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedCard
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.network.ChangeOfferDto
import com.vayunmathur.travel.network.SliceDto
import com.vayunmathur.travel.util.TravelViewModel
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.Change,
) {
    val detail by viewModel.orderDetail.collectAsStateWithLifecycle()
    val change by viewModel.change.collectAsStateWithLifecycle()

    LaunchedEffect(route.orderId) {
        viewModel.resetChange()
        if (detail.order?.orderId != route.orderId) viewModel.loadOrderDetail(route.orderId)
    }

    var selectedSliceIndex by remember { mutableStateOf(0) }
    var newDate by remember { mutableStateOf("") }

    AppScaffold(
        title = stringResource(R.string.change_flights),
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        val order = detail.order
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (change.done) {
                IconCheckCircle(
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(stringResource(R.string.change_confirmed), style = MaterialTheme.typography.headlineSmall)
                Button(
                    onClick = {
                        viewModel.resetChange()
                        backStack.reset(Route.Home, Route.Trips)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(UiR.string.done)) }
                return@Column
            }

            if (order == null) {
                StatusBox(loading = detail.loading, error = detail.error, isEmpty = !detail.loading)
                return@Column
            }

            if (!change.requested) {
                Text(stringResource(R.string.pick_a_flight_to_change), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                order.slices.forEachIndexed { index, slice ->
                    SliceChoice(slice, selected = index == selectedSliceIndex) { selectedSliceIndex = index }
                }
                DateField(stringResource(R.string.new_date), newDate, onDate = { newDate = it }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        val slice = order.slices[selectedSliceIndex]
                        viewModel.requestChange(
                            orderId = route.orderId,
                            removeSliceId = slice.id,
                            origin = slice.origin,
                            destination = slice.destination,
                            newDate = newDate,
                            cabin = "",
                        )
                    },
                    enabled = newDate.isNotBlank() && order.slices.getOrNull(selectedSliceIndex)?.id?.isNotBlank() == true,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.find_change_options)) }
            } else {
                if (change.loading) {
                    StatusBox(loading = true, error = null, isEmpty = false)
                } else if (change.error != null) {
                    Text(change.error!!, color = MaterialTheme.colorScheme.error)
                } else if (change.offers.isEmpty()) {
                    Text(stringResource(R.string.no_change_options_available), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(stringResource(R.string.change_options), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                    change.offers.forEach { offer ->
                        ChangeOfferCard(offer, confirming = change.confirming) {
                            viewModel.confirmChange(route.orderId, offer.id)
                        }
                    }
                    change.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun SliceChoice(slice: SliceDto, selected: Boolean, onClick: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("${slice.origin} → ${slice.destination}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    TravelViewModel.prettyDate(slice.departureAt.take(10)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                IconCheckCircle(tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ChangeOfferCard(offer: ChangeOfferDto, confirming: Boolean, onSelect: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            offer.slices.forEach { slice ->
                Text(
                    "${slice.origin} → ${slice.destination} · ${TravelViewModel.prettyDate(slice.departureAt.take(10))}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            HorizontalDivider()
            val diff = offer.changeTotalAmount.toDoubleOrNull() ?: 0.0
            Text(
                when {
                    diff > 0 -> stringResource(R.string.extra_to_pay, formatMoney(offer.changeTotalAmount, offer.changeTotalCurrency))
                    diff < 0 -> stringResource(R.string.refund_1, formatMoney((-diff).toString(), offer.changeTotalCurrency))
                    else -> stringResource(R.string.no_price_change)
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Button(onClick = onSelect, enabled = !confirming, modifier = Modifier.fillMaxWidth()) {
                Text(if (confirming) stringResource(R.string.confirming) else stringResource(R.string.select_confirm))
            }
        }
    }
}

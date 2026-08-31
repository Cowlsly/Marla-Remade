package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconCheckCircle
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.network.StayRateDto
import com.vayunmathur.travel.network.StaySearchResultDto
import com.vayunmathur.travel.network.StayGuestInputDto
import com.vayunmathur.travel.util.StayBookingState
import com.vayunmathur.travel.util.StayResultsActions
import com.vayunmathur.travel.util.StaySearchState
import com.vayunmathur.travel.util.TravelViewModel
import androidx.compose.ui.res.stringResource

/**
 * Hotel search form (used from Home when the "Stays" product is selected):
 * location autocomplete, check-in/out dates, rooms and guests.
 */
@Composable
fun StaySearchForm(
    viewModel: TravelViewModel,
    modifier: Modifier = Modifier,
    onSearch: (place: String, checkIn: String, checkOut: String, rooms: Int, adults: Int, latitude: Double?, longitude: Double?) -> Unit,
) {
    var place by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf<Double?>(null) }
    var longitude by remember { mutableStateOf<Double?>(null) }
    var checkIn by remember { mutableStateOf("") }
    var checkOut by remember { mutableStateOf("") }
    var rooms by remember { mutableIntStateOf(1) }
    var adults by remember { mutableIntStateOf(2) }

    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StaySuggestField(
            label = stringResource(com.vayunmathur.travel.R.string.destination_or_hotel),
            viewModel = viewModel,
            onSelect = { name, lat, lng ->
                place = name
                latitude = lat
                longitude = lng
            },
        )
        DateField(stringResource(R.string.check_in), checkIn, onDate = { checkIn = it })
        DateField(stringResource(R.string.check_out), checkOut, onDate = { checkOut = it })
        CountStepper(stringResource(R.string.rooms), rooms, onCount = { rooms = it }, min = 1, max = 5)
        CountStepper(stringResource(R.string.guests), adults, onCount = { adults = it }, min = 1, max = 9)
        Button(
            onClick = { onSearch(place, checkIn, checkOut, rooms, adults, latitude, longitude) },
            enabled = place.isNotBlank() && checkIn.isNotBlank() && checkOut.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.search_hotels)) }
    }
}

/**
 * Stays location autocomplete: type a city or hotel name and pick a suggestion,
 * which carries coordinates so the search can run without an airport code.
 */
@Composable
private fun StaySuggestField(
    label: String,
    viewModel: TravelViewModel,
    onSelect: (name: String, latitude: Double?, longitude: Double?) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<com.vayunmathur.travel.network.StaySuggestionDto>>(emptyList()) }
    var justSelected by remember { mutableStateOf(false) }

    LaunchedEffect(text) {
        if (justSelected) {
            justSelected = false
            return@LaunchedEffect
        }
        if (text.length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(250)
        suggestions = viewModel.staySuggestions(text)
    }

    Column {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                // Free-text fallback: pass the typed text with no coordinates so
                // the server resolves it as an IATA code.
                onSelect(it.trim(), null, null)
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (suggestions.isNotEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Column {
                    suggestions.take(6).forEach { s ->
                        com.vayunmathur.library.ui.ListItem(
                            modifier = Modifier.clickable {
                                onSelect(s.name, s.latitude, s.longitude)
                                text = s.name
                                justSelected = true
                                suggestions = emptyList()
                            },
                        ) { Text(s.name) }
                    }
                }
            }
        }
    }
}

/** Binds [TravelViewModel] and the back stack to the stateless [StayResultsScreen]. */
@Composable
fun StayResultsPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.StayResults,
) {
    val state by viewModel.stayResults.collectAsStateWithLifecycle()
    LaunchedEffect(route) {
        val lat = route.latitude.takeIf { !it.isNaN() }
        val lng = route.longitude.takeIf { !it.isNaN() }
        viewModel.searchStays(route.place, route.checkIn, route.checkOut, route.rooms, route.adults, lat, lng)
    }
    val actions = remember(backStack) {
        object : StayResultsActions {
            override fun openStay(result: StaySearchResultDto) =
                backStack.add(Route.StayDetail(result.id, result.name))

            override fun back() = backStack.pop()
        }
    }
    StayResultsScreen(place = route.place, state = state, actions = actions)
}

/**
 * The hotel list, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StayResultsScreen(
    place: String,
    state: StaySearchState,
    actions: StayResultsActions,
) {
    LazyListScaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hotels_in, place)) },
                navigationIcon = { IconNavigation { actions.back() } },
            )
        },
        scrollBehavior = appBarScrollBehavior(),
    ) {
        items(state.results) { result ->
            StayResultCard(result) { actions.openStay(result) }
        }
        if (state.loading || state.error != null || (state.hasSearched && state.results.isEmpty())) {
            item {
                StatusBox(
                    loading = state.loading,
                    error = state.error,
                    isEmpty = state.hasSearched && state.results.isEmpty(),
                    emptyMessage = "No hotels found for those dates.",
                )
            }
        }
    }
}

@Composable
private fun StayResultCard(result: StaySearchResultDto, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { onClick() },
    ) {
        Column {
            if (result.photoUrl.isNotBlank()) {
                AsyncImage(
                    model = result.photoUrl,
                    contentDescription = result.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .sharedContainer("travel-stay-photo-${result.id}"),
                )
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        result.name.ifBlank { stringResource(R.string.hotel) },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.sharedText("travel-stay-name-${result.id}"),
                    )
                    Text(
                        starLabel(result.rating, result.reviewScore),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.sharedText("travel-stay-rating-${result.id}"),
                    )
                    if (result.address.isNotBlank()) {
                        Text(result.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (result.amenities.isNotEmpty()) {
                        Text(
                            result.amenities.take(4).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.from), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        formatMoney(result.cheapestAmount, result.cheapestCurrency),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StayDetailPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.StayDetail,
) {
    val state by viewModel.stayRates.collectAsStateWithLifecycle()
    LaunchedEffect(route.searchResultId) { viewModel.loadStayRates(route.searchResultId, route.name) }

    AppScaffold(
        title = route.name.ifBlank { stringResource(R.string.hotel) },
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        val rates = state.rates
        if (rates == null) {
            StatusBox(loading = state.loading, error = state.error, isEmpty = !state.loading, modifier = Modifier.padding(padding))
            return@AppScaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            rates.photos.firstOrNull()?.let { photo ->
                AsyncImage(
                    model = photo,
                    contentDescription = rates.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .sharedContainer("travel-stay-photo-${route.searchResultId}"),
                )
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    rates.name.ifBlank { stringResource(R.string.hotel) },
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.sharedText("travel-stay-name-${route.searchResultId}"),
                )
                Text(
                    starLabel(rates.rating, rates.reviewScore),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.sharedText("travel-stay-rating-${route.searchResultId}"),
                )
                if (rates.address.isNotBlank()) {
                    Text(rates.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (rates.amenities.isNotEmpty()) {
                    Text(
                        rates.amenities.take(8).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            SectionHeader(stringResource(R.string.rooms))
            rates.rooms.forEach { room ->
                Text(
                    room.name.ifBlank { stringResource(R.string.room) },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                room.rates.forEach { rate ->
                    RateRow(rate) {
                        viewModel.selectStayRate(rate)
                        backStack.add(Route.StayGuests)
                    }
                }
            }
            Column(Modifier.padding(bottom = 24.dp)) {}
        }
    }
}

@Composable
private fun RateRow(rate: StayRateDto, onSelect: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { onSelect() },
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rate.boardType.ifBlank { stringResource(R.string.room_only) }.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.bodyLarge)
                Text(rate.cancellation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                formatMoney(rate.totalAmount, rate.totalCurrency),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StayGuestsPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
) {
    val booking by viewModel.stayBooking.collectAsStateWithLifecycle()
    var givenName by remember { mutableStateOf("") }
    var familyName by remember { mutableStateOf("") }
    var bornOn by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var loyaltyProgramme by remember { mutableStateOf("") }
    var loyaltyNumber by remember { mutableStateOf("") }
    val (amount, currency) = viewModel.stayTotal()

    LaunchedEffect(booking) {
        val b = booking
        if (b is StayBookingState.Success) {
            viewModel.resetStayBooking()
            backStack.reset(Route.Home, Route.StayConfirmation(b.result.id))
        }
    }

    AppScaffold(
        title = stringResource(R.string.guest_details),
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        val loading = booking is StayBookingState.Loading
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.total), style = MaterialTheme.typography.titleMedium)
                    Text(
                        formatMoney(amount, currency),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            OutlinedTextField(givenName, { givenName = it }, label = { Text(stringResource(R.string.given_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(familyName, { familyName = it }, label = { Text(stringResource(R.string.family_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            DateField(stringResource(R.string.date_of_birth), bornOn, onDate = { bornOn = it }, dateFormat = DateString::monthDayYear)
            OutlinedTextField(
                email, { email = it }, label = { Text(stringResource(R.string.email)) }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                phone, { phone = it }, label = { Text(stringResource(R.string.phone_e_g_14155550123)) }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.hotel_loyalty_optional), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    loyaltyProgramme, { loyaltyProgramme = it }, label = { Text(stringResource(R.string.programme)) }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    loyaltyNumber, { loyaltyNumber = it }, label = { Text(stringResource(R.string.number)) }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            (booking as? StayBookingState.Error)?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                stringResource(R.string.sandbox_booking_paid_with_a_duffel_test),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    val loyalty = if (loyaltyNumber.isNotBlank()) {
                        com.vayunmathur.travel.network.StayLoyaltyAccountDto(
                            programmeName = loyaltyProgramme.trim(),
                            accountNumber = loyaltyNumber.trim(),
                        )
                    } else {
                        null
                    }
                    viewModel.bookStay(
                        StayGuestInputDto(givenName, familyName, bornOn, loyaltyProgrammeAccount = loyalty),
                        email,
                        phone,
                    )
                },
                enabled = !loading && givenName.isNotBlank() && familyName.isNotBlank() && bornOn.isNotBlank() && email.contains("@") && phone.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.booking))
                } else {
                    Text(stringResource(R.string.book_with_test_balance))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StayConfirmationPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.StayConfirmation,
) {
    val trips by viewModel.bookedTrips.collectAsStateWithLifecycle()
    val trip = trips.find { it.orderId == route.bookingId }

    DetailScaffold(title = stringResource(R.string.booking_confirmed), scrollBehavior = appBarScrollBehavior()) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconCheckCircle(
                modifier = Modifier.size(64.dp).padding(top = 8.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.you_re_booked), style = MaterialTheme.typography.headlineSmall)
            if (trip != null) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StayDetailRow("Reference", trip.bookingReference, emphasize = true)
                        HorizontalDivider()
                        StayDetailRow("Hotel", trip.route)
                        StayDetailRow("Check-in", TravelViewModel.prettyDate(trip.departDate))
                        StayDetailRow("Amount", formatMoney(trip.amount, trip.currency))
                    }
                }
            }
            Button(onClick = { backStack.reset(Route.Home) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(UiR.string.done)) }
        }
    }
}

@Composable
private fun StayDetailRow(label: String, value: String, emphasize: Boolean = false) {
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

/** "★★★★★ · 8.7/10" from a star rating + review score (either may be absent). */
private fun starLabel(rating: Long, reviewScore: Double): String {
    val stars = if (rating in 1..5) "★".repeat(rating.toInt()) else ""
    val score = if (reviewScore > 0) "${"%.1f".format(reviewScore)}/10" else ""
    return listOf(stars, score).filter { it.isNotBlank() }.joinToString(" · ")
}

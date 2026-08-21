package com.vayunmathur.taxi.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.RasterMap
import com.vayunmathur.library.map.rememberCameraState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.IconMyLocation
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.taxi.R
import com.vayunmathur.taxi.data.BookingTrip
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.QuoteResult
import com.vayunmathur.taxi.data.RideQuote
import com.vayunmathur.taxi.platform.deeplink.RideDeepLinks
import com.vayunmathur.taxi.platform.location.LocationProvider
import com.vayunmathur.taxi.provider.QuoteRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln

private val PickupDotColor = Color(0xFF2FBF71)
private val DestinationPinColor = Color(0xFFE23744)

private fun providerColor(provider: Provider): Color = when (provider) {
    Provider.LYFT -> Color(0xFFEA0B8C)
    Provider.UBER -> Color(0xFF276EF1)
}

private enum class RouteField { PICKUP, DESTINATION }

@Composable
fun RideScreen(bookingTrip: MutableState<BookingTrip?>? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val camera = rememberCameraState(CameraPosition(GeoPoint(-122.4194, 37.7749), 12.0))

    var calculatedPickup by remember { mutableStateOf<Place?>(null) }
    var pickup by remember { mutableStateOf<Place?>(null) }
    var pickupQuery by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf<Place?>(null) }
    var destinationQuery by remember { mutableStateOf("") }
    var active by remember { mutableStateOf<RouteField?>(null) }
    var suggestions by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<Map<Provider, QuoteResult>>(emptyMap()) }
    var comparing by remember { mutableStateOf(false) }
    var booking by remember { mutableStateOf<LyftBookingRequest?>(null) }

    // Lyft cost tokens expire; re-quote shortly before the soonest one lapses so a booking never
    // uses a stale token. Re-runs whenever results change (each refresh reschedules the next).
    LaunchedEffect(results, pickup, destination) {
        val from = pickup ?: return@LaunchedEffect
        val to = destination ?: return@LaunchedEffect
        val success = results[Provider.LYFT] as? QuoteResult.Success ?: return@LaunchedEffect
        val soonestExpiry = success.quotes.mapNotNull { it.costTokenExpiryMs }.minOrNull()
            ?: return@LaunchedEffect
        // Only schedule when the value looks like a future epoch-ms deadline; otherwise skip to
        // avoid a tight refresh loop on an unexpected unit.
        val leadMs = soonestExpiry - System.currentTimeMillis() - 30_000
        if (leadMs <= 0) return@LaunchedEffect
        delay(leadMs)
        results = QuoteRepository.refresh(context, from, to, results)
    }

    LaunchedEffect(Unit) {
        val here = LocationProvider.current(context) ?: return@LaunchedEffect
        val place = LocationProvider.describe(context, here)
        calculatedPickup = place
        if (pickup == null) {
            pickup = place
            pickupQuery = place.name
        }
        camera.animateTo(CameraPosition(GeoPoint(here.longitude, here.latitude), 14.0))
    }

    // Debounced lookup for whichever field is currently being edited.
    LaunchedEffect(active, pickupQuery, destinationQuery) {
        val field = active
        if (field == null) {
            suggestions = emptyList()
            searching = false
            return@LaunchedEffect
        }
        val q = (if (field == RouteField.PICKUP) pickupQuery else destinationQuery).trim()
        val selected = if (field == RouteField.PICKUP) pickup?.name else destination?.name
        if (q.isEmpty() || q == selected) {
            suggestions = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(300)
        suggestions = LocationProvider.search(context, q)
        searching = false
    }

    fun quote() {
        val from = pickup ?: return
        val to = destination ?: return
        scope.launch {
            comparing = true
            // Frame both endpoints: centre between them, zoom from how far apart they are.
            val centre = GeoPoint(
                (from.location.longitude + to.location.longitude) / 2.0,
                (from.location.latitude + to.location.latitude) / 2.0,
            )
            val spread = maxOf(
                abs(from.location.longitude - to.location.longitude),
                abs(from.location.latitude - to.location.latitude),
            )
            val zoom = if (spread <= 0.0) 14.0 else (ln(360.0 / spread) / ln(2.0) - 1.0).coerceIn(10.0, 15.0)
            camera.animateTo(CameraPosition(centre, zoom))
            results = QuoteRepository.quotes(context, from, to)
            comparing = false
        }
    }

    fun select(place: Place) {
        when (active) {
            RouteField.PICKUP -> {
                pickup = place
                pickupQuery = place.name
            }
            RouteField.DESTINATION -> {
                destination = place
                destinationQuery = place.name
            }
            null -> return
        }
        suggestions = emptyList()
        active = null
        focus.clearFocus()
        quote()
    }

    fun resetPickup() {
        scope.launch {
            val place = calculatedPickup
                ?: LocationProvider.current(context)?.let { here ->
                    LocationProvider.describe(context, here).also { calculatedPickup = it }
                }
                ?: return@launch
            pickup = place
            pickupQuery = place.name
            active = null
            suggestions = emptyList()
            focus.clearFocus()
            quote()
        }
    }

    fun runSearch() {
        val field = active ?: return
        scope.launch {
            val q = (if (field == RouteField.PICKUP) pickupQuery else destinationQuery).trim()
            val current = if (field == RouteField.PICKUP) pickup else destination
            val place = current
                ?: suggestions.firstOrNull()
                ?: LocationProvider.search(context, q).firstOrNull()
            if (place != null) select(place)
        }
    }

    // A trip handed in from the maps route sheet via the taxi://book deep link: pre-fill both
    // endpoints and immediately compare fares. Consumed once, then cleared.
    LaunchedEffect(bookingTrip?.value) {
        val state = bookingTrip ?: return@LaunchedEffect
        val trip = state.value ?: return@LaunchedEffect
        pickup = trip.pickup
        pickupQuery = trip.pickup.name
        destination = trip.destination
        destinationQuery = trip.destination.name
        suggestions = emptyList()
        active = null
        state.value = null
        quote()
    }

    AppScaffold(title = stringResource(R.string.nav_ride), scrollBehavior = appBarScrollBehavior()) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)),
            ) {
                RasterMap(cameraState = camera, modifier = Modifier.fillMaxSize())
                // Overlay pins for pickup and destination, positioned from the live camera
                // projection so they track pan/zoom (same pattern as fooddelivery's map).
                val projection = camera.projection
                if (projection != null) {
                    pickup?.let {
                        MapPin(
                            projection.screenLocationFromPosition(
                                GeoPoint(it.location.longitude, it.location.latitude),
                            ),
                            PickupDotColor,
                        ) { IconMyLocation(tint = Color.White) }
                    }
                    destination?.let {
                        MapPin(
                            projection.screenLocationFromPosition(
                                GeoPoint(it.location.longitude, it.location.latitude),
                            ),
                            DestinationPinColor,
                        ) { IconLocationOn(tint = Color.White) }
                    }
                }
            }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RouteInputs(
                    pickupQuery = pickupQuery,
                    onPickupChange = {
                        pickupQuery = it
                        active = RouteField.PICKUP
                        if (it != pickup?.name) {
                            pickup = null
                            results = emptyMap()
                        }
                    },
                    onPickupFocus = { active = RouteField.PICKUP },
                    onResetPickup = { resetPickup() },
                    destinationQuery = destinationQuery,
                    onDestinationChange = {
                        destinationQuery = it
                        active = RouteField.DESTINATION
                        if (it != destination?.name) {
                            destination = null
                            results = emptyMap()
                        }
                    },
                    onDestinationFocus = { active = RouteField.DESTINATION },
                    onClearDestination = {
                        destinationQuery = ""
                        destination = null
                        suggestions = emptyList()
                        results = emptyMap()
                        active = RouteField.DESTINATION
                    },
                    onSearch = { runSearch() },
                )

                if (suggestions.isNotEmpty() && active != null) {
                    SuggestionList(suggestions) { select(it) }
                } else if (searching && active != null) {
                    LoadingRow(stringResource(R.string.searching))
                }
            }

            ResultsSection(
                results = results,
                comparing = comparing,
                hasRoute = pickup != null && destination != null,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { provider, quote ->
                val from = pickup
                val to = destination
                if (from != null && to != null) {
                    // A signed-in Lyft fare (has an offer id) books in-app; everything else —
                    // deep-links to the provider as before.
                    if (provider == Provider.LYFT && quote?.offerId != null) {
                        val session = (results[Provider.LYFT] as? QuoteResult.Success)?.purchaseSessionId
                        booking = LyftBookingRequest(quote, from, to, session)
                    } else {
                        RideDeepLinks.openBooking(context, provider, from, to, quote)
                    }
                }
            }
        }

        booking?.let { request ->
            LyftBookingFlow(request = request, onDismiss = { booking = null })
        }
    }
}

@Composable
private fun RouteInputs(
    pickupQuery: String,
    onPickupChange: (String) -> Unit,
    onPickupFocus: () -> Unit,
    onResetPickup: () -> Unit,
    destinationQuery: String,
    onDestinationChange: (String) -> Unit,
    onDestinationFocus: () -> Unit,
    onClearDestination: () -> Unit,
    onSearch: () -> Unit,
) {
    OutlinedTextField(
        value = pickupQuery,
        onValueChange = onPickupChange,
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onPickupFocus() },
        label = { Text(stringResource(R.string.pickup_label)) },
        placeholder = { Text(stringResource(R.string.pickup_placeholder)) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        leadingIcon = { Box(Modifier.size(11.dp).clip(CircleShape).background(PickupDotColor)) },
        trailingIcon = {
            IconButton(onClick = onResetPickup) {
                IconMyLocation(tint = MaterialTheme.colorScheme.primary)
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )

    OutlinedTextField(
        value = destinationQuery,
        onValueChange = onDestinationChange,
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onDestinationFocus() },
        label = { Text(stringResource(R.string.destination)) },
        placeholder = { Text(stringResource(R.string.search_placeholder)) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        leadingIcon = { IconLocationOn(tint = DestinationPinColor) },
        trailingIcon = {
            if (destinationQuery.isNotEmpty()) {
                IconButton(onClick = onClearDestination) { IconClose() }
            } else {
                IconSearch(tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

@Composable
private fun SuggestionList(suggestions: List<Place>, onSelect: (Place) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            suggestions.forEachIndexed { index, place ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(place) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconLocationOn(tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            place.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val address = place.address
                        if (address != null && address != place.name) {
                            Text(
                                address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (index < suggestions.lastIndex) {
                    HorizontalDivider(Modifier.padding(start = 48.dp))
                }
            }
        }
    }
}

@Composable
private fun ResultsSection(
    results: Map<Provider, QuoteResult>,
    comparing: Boolean,
    hasRoute: Boolean,
    modifier: Modifier = Modifier,
    onBook: (Provider, RideQuote?) -> Unit,
) {
    when {
        comparing -> Column(
            modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.comparing_fares),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        !hasRoute -> EmptyPrompt(modifier)

        else -> {
            val quotes = results.values
                .filterIsInstance<QuoteResult.Success>()
                .flatMap { it.quotes }
                .sortedBy { it.fareLowMinor }
            val notes = results.entries
                .filter { it.value !is QuoteResult.Success }
                .map { it.key to it.value }
            val cheapest = quotes.firstOrNull()

            LazyColumn(
                modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (quotes.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.choose_ride)) }
                    items(quotes) { quote ->
                        RideOptionCard(
                            quote = quote,
                            isCheapest = quote === cheapest,
                            onBook = { onBook(quote.provider, quote) },
                        )
                    }
                }
                items(notes) { (provider, result) ->
                    ProviderNoteCard(provider, result) { onBook(provider, null) }
                }
                if (quotes.isEmpty() && notes.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.no_places_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RideOptionCard(quote: RideQuote, isCheapest: Boolean, onBook: () -> Unit) {
    Card(
        onClick = onBook,
        modifier = Modifier.fillMaxWidth(),
        border = if (isCheapest) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderBadge(quote.provider)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        quote.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isCheapest) {
                        Tag(
                            text = stringResource(R.string.best_price),
                            container = MaterialTheme.colorScheme.primaryContainer,
                            content = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                val sub = listOfNotNull(
                    quote.pickupEtaMinutes?.let { stringResource(R.string.eta_minutes, it) },
                    quote.capacity?.let { stringResource(R.string.seats, it) },
                ).joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (quote.hasDiscount) {
                    Text(
                        formatOriginalFare(quote),
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = TextDecoration.LineThrough,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    formatFare(quote),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (quote.hasDiscount) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                quote.surgeMultiplier?.takeIf { it > 1.0 }?.let {
                    Text(
                        stringResource(R.string.surge_short, "%.1f".format(it)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderNoteCard(provider: Provider, result: QuoteResult, onBook: () -> Unit) {
    val message = when (result) {
        is QuoteResult.NotSignedIn -> stringResource(R.string.connect_prompt, provider.label)
        is QuoteResult.Failed -> result.message
        else -> ""
    }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderBadge(provider)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    provider.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onBook) {
                Text(stringResource(R.string.book_with, provider.label))
            }
        }
    }
}

@Composable
private fun ProviderBadge(provider: Provider) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(providerColor(provider)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            provider.label.take(1),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** A circular marker centred on [at] (a viewport offset from the camera projection). */
@Composable
private fun MapPin(at: DpOffset, color: Color, icon: @Composable () -> Unit) {
    val size = 32.dp
    Box(Modifier.offset(at.x - size / 2, at.y - size / 2)) {
        Surface(color = color, shape = CircleShape, modifier = Modifier.size(size)) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
    }
}

@Composable
private fun Tag(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = container) {
        Text(
            text,
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun LoadingRow(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyPrompt(modifier: Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconSearch(Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.enter_destination),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatFare(quote: RideQuote): String {
    fun money(minor: Long) = "$%.2f".format(minor / 100.0)
    return if (quote.isRange) {
        "${money(quote.fareLowMinor)} – ${money(quote.fareHighMinor)}"
    } else {
        money(quote.fareLowMinor)
    }
}

/** The pre-discount price, for a struck-through "was" label when a promotion applies. */
private fun formatOriginalFare(quote: RideQuote): String {
    fun money(minor: Long) = "$%.2f".format(minor / 100.0)
    val low = quote.originalFareLowMinor ?: quote.fareLowMinor
    val high = quote.originalFareHighMinor ?: quote.fareHighMinor
    return if (low != high) "${money(low)} – ${money(high)}" else money(low)
}

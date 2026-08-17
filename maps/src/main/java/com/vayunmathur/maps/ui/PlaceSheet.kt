package com.vayunmathur.maps.ui
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.util.DateNameStyle
import com.vayunmathur.library.util.localizedDayOfWeekNames
import kotlinx.datetime.isoDayNumber
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconDirections
import com.vayunmathur.library.ui.IconDirectionsWalk
import com.vayunmathur.library.ui.IconGlobe
import com.vayunmathur.library.ui.IconMenuBook
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSchedule
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconShoppingCart
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.IconStarBorder
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.vayunmathur.library.util.firstLetterUppercase
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.OpeningHours
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.GooglePoiInfo
import com.vayunmathur.maps.data.google.StreetViewDataSource
import com.vayunmathur.maps.ipc.rememberOrderDeepLink
import com.vayunmathur.maps.data.google.StreetViewPano
import com.vayunmathur.maps.ui.streetview.StreetViewScreen
import com.vayunmathur.maps.data.timeFormat
import com.vayunmathur.maps.util.SavedPlacesViewModel
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.toLocalDateTime
import kotlin.collections.iterator
import kotlin.time.Clock

fun goto(context: Context, uri: String) {
    val intent = Intent(Intent.ACTION_VIEW, uri.toUri())
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ExternalIntents.launch(context, intent)
}

/**
 * Vela-style place-details sheet: a compact header (name / category · price /
 * rating / open-now), a horizontal action row, then the keyless Google
 * enrichment (editorial, photos, popular times, reviews).
 *
 * This is a LAYOUT rework only — the data still comes from
 * [SelectedFeatureViewModel.currentPoiInfo] (the [GooglePoiEnrichment] source)
 * and the "Directions" action still feeds MA's existing on-device router via
 * [requestDirections] (which turns the selection into a [SpecificFeature.Route]).
 * Every Google field is optional and guarded, so a bot-degraded scrape simply
 * renders fewer sections.
 */
@Composable
fun PlaceSheet(
    viewModel: SelectedFeatureViewModel,
    savedPlacesViewModel: SavedPlacesViewModel,
    inactiveNavigation: SpecificFeature.Route?,
    feature: SpecificFeature.Restaurant,
    requestDirections: () -> Unit,
) = PlaceSheetContent(
    viewModel, savedPlacesViewModel, inactiveNavigation, feature,
    phone = feature.phone, website = feature.website, menu = feature.menu,
    openingHours = feature.openingHours, requestDirections = requestDirections,
)

@Composable
fun PlaceSheet(
    viewModel: SelectedFeatureViewModel,
    savedPlacesViewModel: SavedPlacesViewModel,
    inactiveNavigation: SpecificFeature.Route?,
    feature: SpecificFeature.GenericPlace,
    requestDirections: () -> Unit,
) = PlaceSheetContent(
    viewModel, savedPlacesViewModel, inactiveNavigation, feature,
    phone = feature.phone, website = feature.website, menu = null,
    openingHours = feature.openingHours, requestDirections = requestDirections,
)

@Composable
private fun PlaceSheetContent(
    viewModel: SelectedFeatureViewModel,
    savedPlacesViewModel: SavedPlacesViewModel,
    inactiveNavigation: SpecificFeature.Route?,
    feature: SpecificFeature.RoutableFeature,
    phone: String?,
    website: String?,
    menu: String?,
    openingHours: OpeningHours?,
    requestDirections: () -> Unit,
) {
    val poi by viewModel.currentPoiInfo.collectAsState()
    val context = LocalContext.current
    // If this is a restaurant/food place, ask fooddelivery whether it's orderable
    // (off the main thread, null-safe). Absent/not-orderable → null → no Order button.
    val orderDeepLink by rememberOrderDeepLink(context, feature, poi?.category)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PlaceHeader(feature.name, poi, hasOsmHours = openingHours != null)
        PlaceActionRow(
            feature = feature,
            phone = phone,
            website = website,
            inactiveNavigation = inactiveNavigation,
            savedPlacesViewModel = savedPlacesViewModel,
            requestDirections = requestDirections,
            orderDeepLink = orderDeepLink,
        )
        menu?.let {
            RestaurantItem({ IconMenuBook() }, stringResource(R.string.menu_label)) { goto(context, it) }
        }
        openingHours?.let { OsmHours(it) }
        StreetViewEntry(feature)
        // Reuse the existing enrichment sections (editorial / photos / popular
        // times / reviews). The header already shows category · price, so the
        // enrichment's own subtitle is suppressed to avoid duplication.
        poi?.let { GooglePoiEnrichment(it, hasOsmHours = openingHours != null, showSubtitle = false) }
    }
}

/**
 * "Street View" place-sheet entry (v1 entry point per the port plan). On selection
 * we do a keyless nearest-pano lookup for the place's position; the row only
 * appears when imagery exists nearby. Tapping it opens the full-screen
 * [StreetViewScreen] in a dialog (the photos-style pan/zoom pano viewer).
 */
@Composable
private fun StreetViewEntry(feature: SpecificFeature.RoutableFeature) {
    var pano by remember(feature.position) { mutableStateOf<StreetViewPano?>(null) }
    var show by remember(feature.position) { mutableStateOf(false) }

    LaunchedEffect(feature.position) {
        pano = StreetViewDataSource.nearest(feature.position.latitude, feature.position.longitude)
    }

    pano?.let { p ->
        RestaurantItem({ IconDirectionsWalk() }, stringResource(R.string.street_view)) { show = true }
        if (show) {
            Dialog(
                onDismissRequest = { show = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                StreetViewScreen(initialPano = p, onClose = { show = false })
            }
        }
    }
}

@Composable
private fun PlaceHeader(name: String, poi: GooglePoiInfo?, hasOsmHours: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.titleLarge)

        val subtitle = listOfNotNull(poi?.category?.ifBlank { null }, poi?.priceText?.ifBlank { null })
            .joinToString(" \u00B7 ")
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        poi?.rating?.let { rating ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RatingStars(rating)
                Text(
                    stringResource(R.string.reviews_summary, rating, poi.reviewCount ?: 0),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Open-now status. When OSM opening hours are present the expandable
        // hours block below shows this instead, so only surface Google's status
        // string when we have nothing better.
        if (!hasOsmHours) {
            poi?.statusText?.ifBlank { null }?.let { status ->
                val color = when (poi.openNow) {
                    true -> MaterialTheme.colorScheme.tertiary
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(status, style = MaterialTheme.typography.bodyMedium, color = color)
            }
        }
    }
}

@Composable
private fun RatingStars(rating: Double) {
    Row {
        repeat(5) { i ->
            if (i < rating.toInt()) IconStar(Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
            else IconStarBorder(Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlaceActionRow(
    feature: SpecificFeature.RoutableFeature,
    phone: String?,
    website: String?,
    inactiveNavigation: SpecificFeature.Route?,
    savedPlacesViewModel: SavedPlacesViewModel,
    requestDirections: () -> Unit,
    orderDeepLink: String?,
) {
    val context = LocalContext.current
    val saved by savedPlacesViewModel.saved.collectAsState()
    val isSaved = saved.any { it.matches(feature) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlaceAction(
            Modifier.weight(1f),
            { IconDirections() },
            stringResource(if (inactiveNavigation == null) R.string.directions else R.string.add_stop_to_route),
            onClick = requestDirections,
        )
        phone?.let {
            PlaceAction(Modifier.weight(1f), { IconCall() }, stringResource(R.string.place_action_call)) {
                goto(context, "tel:$it")
            }
        }
        website?.let {
            PlaceAction(Modifier.weight(1f), { IconGlobe() }, stringResource(R.string.place_action_website)) {
                goto(context, it)
            }
        }
        // Order (P19): only present when fooddelivery reports this place orderable.
        orderDeepLink?.let { uri ->
            PlaceAction(Modifier.weight(1f), { IconShoppingCart() }, stringResource(R.string.place_action_order)) {
                goto(context, uri)
            }
        }
        PlaceAction(Modifier.weight(1f), { IconShare() }, stringResource(R.string.place_action_share)) {
            sharePlace(context, feature)
        }
        PlaceAction(
            Modifier.weight(1f),
            { IconSave(tint = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
            stringResource(if (isSaved) R.string.place_action_saved else R.string.place_action_save),
        ) {
            if (isSaved) {
                saved.firstOrNull { it.matches(feature) }?.let { savedPlacesViewModel.removeSaved(it) }
            } else {
                savedPlacesViewModel.addSaved(feature)
            }
        }
    }
}

@Composable
private fun PlaceAction(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun sharePlace(context: Context, feature: SpecificFeature.RoutableFeature) {
    val pos = feature.position
    val url = "https://maps.google.com/?q=${pos.latitude},${pos.longitude}"
    val body = context.getString(R.string.place_share_text, feature.name, url)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, body)
    }
    val chooser = Intent.createChooser(send, feature.name).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ExternalIntents.launch(context, chooser)
}

@Composable
private fun OsmHours(openingHours: OpeningHours) {
    var showDetails by remember { mutableStateOf(false) }

    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val isOpen = openingHours.isOpen(now)
    val nextChangeTime = openingHours.nextStatusChangeTime(now)
    val openStr = stringResource(R.string.open_status)
    val closedStr = stringResource(R.string.closed_status)
    val closesAtStr = stringResource(R.string.closes_at, nextChangeTime.time.format(timeFormat))
    val opensAtStr = stringResource(R.string.opens_at, nextChangeTime.time.format(timeFormat))
    val openColor = MaterialTheme.colorScheme.tertiary
    val closedColor = MaterialTheme.colorScheme.error
    val text = AnnotatedString.Builder().apply {
        if (isOpen) withStyle(SpanStyle(openColor)) { append(openStr) } else withStyle(SpanStyle(closedColor)) { append(closedStr) }
        append(" \u2022 ")
        if (isOpen) append(closesAtStr) else append(opensAtStr)
        if (nextChangeTime.date != now.date) append(" ${localizedDayOfWeekNames(DateNameStyle.FULL)[nextChangeTime.date.dayOfWeek.isoDayNumber - 1]}")
    }.toAnnotatedString()
    Column {
        RestaurantItem(
            { IconSchedule() },
            text,
            shape = verticalShape(0, if (showDetails) 2 else 1),
        ) {
            showDetails = !showDetails
        }
        if (showDetails) {
            Spacer(Modifier.padding(2.dp))
            Card(shape = verticalShape(1, 2)) {
                for ((day, hours) in openingHours.openingHours()) {
                    ListItem(
                        { Text(day.name.lowercase().firstLetterUppercase()) },
                        leadingContent = {},
                        trailingContent = { Text(hours) },
                        colors = ListItemDefaults.colors(Color.Transparent),
                    )
                }
            }
        }
    }
}

fun verticalShape(index: Int, count: Int): RoundedCornerShape {
    val top = if (index == 0) 12.dp else 0.dp
    val bottom = if (index == count - 1) 12.dp else 0.dp
    return RoundedCornerShape(top, top, bottom, bottom)
}

@Composable
fun RestaurantItem(icon: @Composable () -> Unit, text: String, shape: Shape = CardDefaults.shape, onClick: () -> Unit) {
    RestaurantItem(icon, AnnotatedString(text), shape, onClick)
}

@Composable
fun RestaurantItem(icon: @Composable () -> Unit, text: AnnotatedString, shape: Shape = CardDefaults.shape, onClick: () -> Unit) {
    Card(shape = shape) {
        ListItem({
            Text(text)
        }, Modifier.clickable(onClick = onClick), leadingContent = {
            icon()
        }, colors = ListItemDefaults.colors(Color.Transparent))
    }
}

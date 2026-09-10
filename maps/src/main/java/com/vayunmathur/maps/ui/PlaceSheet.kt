package com.vayunmathur.maps.ui
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.util.DateNameStyle
import com.vayunmathur.library.util.localizedDayOfWeekNames
import kotlinx.datetime.isoDayNumber
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.verticalShape
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.IconMenuBook
import com.vayunmathur.library.ui.IconSchedule
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.IconStarBorder
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.util.firstLetterUppercase
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.OpeningHours
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.google.GooglePoiInfo
import com.vayunmathur.maps.data.google.PoiSection
import com.vayunmathur.maps.data.timeFormat
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
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
 * The tabbed lower half of a place sheet: the keyless Google enrichment and the
 * OSM-derived rows, for whichever tab is selected.
 *
 * The fixed part above it — name, rating, open-now, the action row and the tab row — is
 * [PlaceSheetHeader], which the scaffold takes as its header slot and measures the peek
 * height from. This half is what expanding the sheet reveals, so it is free to be as
 * tall as it likes: it scrolls inside whatever the sheet's full height turns out to be.
 *
 * The data still comes from [SelectedFeatureViewModel.currentPoiInfo] (the
 * [GooglePoiEnrichment] source). Every Google field is optional and guarded, so a
 * bot-degraded scrape simply renders fewer sections — and fewer tabs.
 */
@Composable
fun PlaceSheet(
    viewModel: SelectedFeatureViewModel,
    feature: SpecificFeature.Restaurant,
    modifier: Modifier = Modifier,
) = PlaceSheetContent(
    viewModel, modifier, menu = feature.menu, openingHours = feature.openingHours,
)

@Composable
fun PlaceSheet(
    viewModel: SelectedFeatureViewModel,
    feature: SpecificFeature.GenericPlace,
    modifier: Modifier = Modifier,
    onDepartures: (() -> Unit)? = null,
) = PlaceSheetContent(
    viewModel, modifier, menu = null, openingHours = feature.openingHours,
    address = feature.address, onDepartures = onDepartures,
)

@Composable
private fun PlaceSheetContent(
    viewModel: SelectedFeatureViewModel,
    modifier: Modifier,
    menu: String?,
    openingHours: OpeningHours?,
    address: String? = null,
    onDepartures: (() -> Unit)? = null,
) {
    val poi by viewModel.currentPoiInfo.collectAsState()
    val selectedSection by viewModel.poiSection.collectAsState()
    val context = LocalContext.current

    // OSM is the source of truth for the weekly schedule: it carries all seven days,
    // and it is what renders. Google's keyless scrape only ever returns *today* (see
    // GooglePoiDataSource.readHours), so it cannot be a schedule — it is a live
    // override for the single day it knows about. We hand that one day to OsmHours,
    // which swaps it into today's row (flagged "Live") and keeps the other six from
    // OSM. Only when OSM has nothing at all do we fall back to showing Google's lone
    // line through the enrichment's own hours section.
    //
    // There is no connectivity check anywhere in this module, and none is needed: a
    // failed request is indistinguishable from "no data", which is exactly the
    // behaviour wanted.
    val todayOverride = googleTodayHours(poi?.hours.orEmpty())
    PlaceTabPanel(
        poi,
        resolvePoiSection(selectedSection, poi),
        showGoogleHours = openingHours == null,
        modifier = modifier,
    ) {
        // Departures (train/transit stations): resolve the nearest Transitous
        // stop by coordinate and open its live board (see MapPage DeparturesSheet).
        onDepartures?.let { open ->
            RestaurantItem({ IconSchedule() }, stringResource(R.string.transit_departures_title)) { open() }
        }
        menu?.let {
            RestaurantItem({ IconMenuBook() }, stringResource(R.string.menu_label)) { goto(context, it) }
        }
        address?.let { AddressRow(it) }
        openingHours?.let { OsmHours(it, todayOverride) }
    }
}

/**
 * Google's lone "today" hours line reduced to (day, hours), or null if it sent nothing
 * usable.
 *
 * The scrape returns at most one entry, shaped `"Tuesday: 6 AM–10 PM"` — the day it
 * names is today (see GooglePoiDataSource.readHours). We key it by that named day rather
 * than by the device clock so a row only ever gets overridden by a line that actually
 * claims to be that day.
 */
private fun googleTodayHours(lines: List<String>): Pair<DayOfWeek, String>? {
    val line = lines.firstOrNull() ?: return null
    val dayName = line.substringBefore(':', "").trim()
    val hours = line.substringAfter(':', "").trim()
    if (hours.isEmpty()) return null
    val day = DayOfWeek.entries.firstOrNull { it.name.equals(dayName, ignoreCase = true) } ?: return null
    return day to hours
}

/**
 * The panel below a place sheet's tab row: the keyless Google enrichment for whichever
 * section is selected, plus the OSM-derived rows on Details.
 *
 * The tab row itself is not here — it is in [PlaceSheetHeader], above the peek line, so
 * the other sections are discoverable without expanding the sheet. This panel is what
 * expanding reveals, and the selection it follows lives on
 * [SelectedFeatureViewModel.poiSection] because the two halves are sibling slots of the
 * scaffold and cannot share a `remember`.
 *
 * The panel scrolls rather than being height-capped. It used to carry a
 * `heightIn(max = 300.dp)`, from before the peek was measured, when this panel's own
 * height was what decided how much of the screen the sheet took. It no longer is:
 * the peek comes from [PlaceSheetHeader] and does not depend on this panel at all,
 * and a drag is bounded by the scaffold at the window minus the status bar. The
 * sheet rests at whatever height the user drags it to, so a cap here would be an
 * invisible ceiling on that drag — the sheet would refuse to grow mid-gesture and
 * leave the reviews scrolling in a box with empty surface under it.
 *
 * @param osmDetails the OSM-derived rows (departures, menu, address, hours), which
 *   belong to the Details tab but are the caller's to build.
 */
@Composable
private fun PlaceTabPanel(
    poi: GooglePoiInfo?,
    section: PoiSection,
    showGoogleHours: Boolean,
    modifier: Modifier = Modifier,
    osmDetails: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            // Per section, not one shared state: the sections are wildly different
            // lengths now that nothing caps them, so carrying a scrolled-down offset
            // from Reviews into Details would open Details at its bottom.
            .verticalScroll(remember(section) { ScrollState(0) })
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (section == PoiSection.DETAILS) osmDetails()
        poi?.let { GooglePoiEnrichment(it, section, showHours = showGoogleHours) }
    }
}

/**
 * The `addr:*` tags as one tappable row.
 *
 * New surface: nothing displayed an address before, because nothing had one —
 * `GoogleSearchResult.address` only ever appeared as a search-row subtitle. Tapping
 * copies it, which is what an address on a sheet is usually for.
 */
@Composable
private fun AddressRow(address: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    RestaurantItem({ IconLocationOn() }, address) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("address", address)))
        }
    }
}

/**
 * Five stars, filled up to [rating].
 *
 * `Double` rather than `Int` because Google's place rating is fractional; a review's own
 * rating is a whole number and converts. There used to be one of these per file, differing
 * only in that parameter type.
 */
@Composable
internal fun RatingStars(rating: Double, modifier: Modifier = Modifier) {
    Row(modifier) {
        repeat(5) { i ->
            if (i < rating.toInt()) IconStar(Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
            else IconStarBorder(Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OsmHours(openingHours: OpeningHours, todayOverride: Pair<DayOfWeek, String>? = null) {
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
    val liveLabel = stringResource(R.string.poi_hours_live)
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
                    // Today's row shows Google's live value when it sent one, tagged so
                    // the swap is visible; the other six stay straight off OSM.
                    val live = todayOverride?.takeIf { it.first == day }?.second
                    val trailing = if (live != null) {
                        AnnotatedString.Builder().apply {
                            append(live)
                            append("  ")
                            withStyle(SpanStyle(openColor)) { append(liveLabel) }
                        }.toAnnotatedString()
                    } else AnnotatedString(hours)
                    ListItem(
                        { Text(day.name.lowercase().firstLetterUppercase()) },
                        leadingContent = {},
                        trailingContent = { Text(trailing) },
                        colors = ListItemDefaults.colors(Color.Transparent),
                    )
                }
            }
        }
    }
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

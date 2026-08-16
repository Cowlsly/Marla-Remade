package com.vayunmathur.maps.ui
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.util.DateNameStyle
import com.vayunmathur.library.util.localizedDayOfWeekNames
import kotlinx.datetime.isoDayNumber
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconGlobe
import com.vayunmathur.library.ui.IconMenuBook
import com.vayunmathur.library.ui.IconSchedule
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.util.firstLetterUppercase
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.timeFormat
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

@Composable
fun RestaurantBottomSheet(viewModel: SelectedFeatureViewModel, inactiveNavigation: SpecificFeature.Route?, feature: SpecificFeature.Restaurant, requestDirections: () -> Unit) {
    val poi by viewModel.currentPoiInfo.collectAsState()
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ListItem({
                Text(
                    feature.name,
                    style = MaterialTheme.typography.titleLarge,
                )
            }, supportingContent = poi?.takeIf { it.rating != null }?.let { info ->
                { Text(stringResource(R.string.reviews_summary, info.rating!!, info.reviewCount ?: 0)) }
            }, trailingContent = {
                Button({requestDirections()}) {
                    if(inactiveNavigation == null) {
                        Text(stringResource(R.string.directions))
                    } else {
                        Text(stringResource(R.string.add_stop_to_route))
                    }
                }
            }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
        }
        feature.openingHours?.let {
            Spacer(Modifier.height(8.dp))
            var showDetails by remember { mutableStateOf(false) }

            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            val isOpen = it.isOpen(now)
            val nextChangeTime = it.nextStatusChangeTime(now)
            val openStr = stringResource(R.string.open_status)
            val closedStr = stringResource(R.string.closed_status)
            val closesAtStr = stringResource(R.string.closes_at, nextChangeTime.time.format(timeFormat))
            val opensAtStr = stringResource(R.string.opens_at, nextChangeTime.time.format(timeFormat))
            val openColor = MaterialTheme.colorScheme.tertiary
            val closedColor = MaterialTheme.colorScheme.error
            val text = AnnotatedString.Builder().apply {
                if(isOpen) withStyle(SpanStyle(openColor)){append(openStr)} else withStyle(SpanStyle(closedColor)){append(closedStr)}
                append(" • ")
                if(isOpen) append(closesAtStr)
                else append(opensAtStr)
                if(nextChangeTime.date != now.date) append(" ${localizedDayOfWeekNames(DateNameStyle.FULL)[nextChangeTime.date.dayOfWeek.isoDayNumber - 1]}")
            }.toAnnotatedString()
            Column {
                RestaurantItem(
                    { IconSchedule() },
                    text,
                    shape = verticalShape(0, if (showDetails) 2 else 1)
                ) {
                    showDetails = !showDetails
                }
                if (showDetails) {
                    Spacer(Modifier.padding(2.dp))
                    Card(shape = verticalShape(1, 2)) {
                        for ((day, hours) in it.openingHours()) {
                            ListItem(
                                { Text(day.name.lowercase().firstLetterUppercase()) },
                                leadingContent = {},
                                trailingContent = { Text(hours) },
                                colors = ListItemDefaults.colors(Color.Transparent)
                            )
                        }
                    }
                }
            }
        }
        feature.menu?.let {
            RestaurantItem({ IconMenuBook() }, stringResource(R.string.menu_label)) { goto(context, it) }
        }
        feature.website?.let {
            // OSM `website` tags can be malformed or non-http (e.g. plain
            // "facebook.com/foo", "mailto:..."). Fall back to the raw
            // string if URI parsing yields no host.
            val label = runCatching { it.toUri().host }.getOrNull()?.takeUnless { h -> h.isBlank() } ?: it
            RestaurantItem({ IconGlobe() }, label) { goto(context, it) }
        }
        feature.phone?.let {
            RestaurantItem({ IconCall() }, it) { goto(context, "tel:$it") }
        }
        poi?.let { GooglePoiEnrichment(it, hasOsmHours = feature.openingHours != null) }
    }
}

@Composable
fun RestaurantBottomSheet(viewModel: SelectedFeatureViewModel, inactiveNavigation: SpecificFeature.Route?, feature: SpecificFeature.GenericPlace, requestDirections: () -> Unit) {
    val poi by viewModel.currentPoiInfo.collectAsState()
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ListItem({
                Text(
                    feature.name,
                    style = MaterialTheme.typography.titleLarge,
                )
            }, supportingContent = poi?.takeIf { it.rating != null }?.let { info ->
                { Text(stringResource(R.string.reviews_summary, info.rating!!, info.reviewCount ?: 0)) }
            }, trailingContent = {
                Button({requestDirections()}) {
                    if(inactiveNavigation == null) {
                        Text(stringResource(R.string.directions))
                    } else {
                        Text(stringResource(R.string.add_stop_to_route))
                    }
                }
            }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
        }
        feature.openingHours?.let {
            Spacer(Modifier.height(8.dp))
            var showDetails by remember { mutableStateOf(false) }

            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            val isOpen = it.isOpen(now)
            val nextChangeTime = it.nextStatusChangeTime(now)
            val openStr = stringResource(R.string.open_status)
            val closedStr = stringResource(R.string.closed_status)
            val closesAtStr = stringResource(R.string.closes_at, nextChangeTime.time.format(timeFormat))
            val opensAtStr = stringResource(R.string.opens_at, nextChangeTime.time.format(timeFormat))
            val openColor = MaterialTheme.colorScheme.tertiary
            val closedColor = MaterialTheme.colorScheme.error
            val text = AnnotatedString.Builder().apply {
                if(isOpen) withStyle(SpanStyle(openColor)){append(openStr)} else withStyle(SpanStyle(closedColor)){append(closedStr)}
                append(" • ")
                if(isOpen) append(closesAtStr)
                else append(opensAtStr)
                if(nextChangeTime.date != now.date) append(" ${localizedDayOfWeekNames(DateNameStyle.FULL)[nextChangeTime.date.dayOfWeek.isoDayNumber - 1]}")
            }.toAnnotatedString()
            Column {
                RestaurantItem(
                    { IconSchedule() },
                    text,
                    shape = verticalShape(0, if (showDetails) 2 else 1)
                ) {
                    showDetails = !showDetails
                }
                if (showDetails) {
                    Spacer(Modifier.padding(2.dp))
                    Card(shape = verticalShape(1, 2)) {
                        for ((day, hours) in it.openingHours()) {
                            ListItem(
                                { Text(day.name.lowercase().firstLetterUppercase()) },
                                leadingContent = {},
                                trailingContent = { Text(hours) },
                                colors = ListItemDefaults.colors(Color.Transparent)
                            )
                        }
                    }
                }
            }
        }
        feature.website?.let {
            val label = runCatching { it.toUri().host }.getOrNull()?.takeUnless { h -> h.isBlank() } ?: it
            RestaurantItem({ IconGlobe() }, label) { goto(context, it) }
        }
        feature.phone?.let {
            RestaurantItem({ IconCall() }, it) { goto(context, "tel:$it") }
        }
        poi?.let { GooglePoiEnrichment(it, hasOsmHours = feature.openingHours != null) }
    }
}

@Composable
fun TransitStopBottomSheet(inactiveNavigation: SpecificFeature.Route?, feature: SpecificFeature.TransitStop, requestDirections: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ListItem({
                Text(
                    feature.name,
                    style = MaterialTheme.typography.titleLarge,
                )
            }, supportingContent = {
                if (feature.stopCode != null || feature.gtfsFeed != null) {
                    val info = listOfNotNull(feature.gtfsFeed, feature.stopCode).joinToString(" / ")
                    Text(info)
                }
            }, trailingContent = {
                Button({requestDirections()}) {
                    if(inactiveNavigation == null) {
                        Text(stringResource(R.string.directions))
                    } else {
                        Text(stringResource(R.string.add_stop_to_route))
                    }
                }
            }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
        }
        RestaurantItem({ IconGlobe() }, stringResource(R.string.travel_mode_transit)) {}
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

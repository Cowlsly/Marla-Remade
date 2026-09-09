package com.vayunmathur.maps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconFuelPrice
import com.vayunmathur.library.ui.IconInfo
import com.vayunmathur.library.ui.IconSchedule
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.firstLetterUppercase
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.google.GooglePoiInfo
import com.vayunmathur.maps.data.google.GoogleReview
import com.vayunmathur.maps.data.google.PoiPopularTimes
import com.vayunmathur.maps.data.google.PoiSection
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Renders one [PoiSection] of the keyless Google Maps POI enrichment.
 *
 * Every part is conditional on its data being present — a keyless response is
 * bot-degraded (popular times and the full photo gallery are usually stripped)
 * and the scrape is fragile (a Google reshape nulls individual fields), so this
 * shows whatever came back and silently omits the rest. Nothing here can throw.
 *
 * The caller owns scrolling and the height bound: each section lays itself out at
 * its natural height inside the sheet's bounded, scrollable tab panel.
 */
@Composable
fun GooglePoiEnrichment(info: GooglePoiInfo, section: PoiSection, showHours: Boolean = true) {
    if (info.isEmpty) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (section) {
            PoiSection.DETAILS -> DetailsSection(info, showHours)
            PoiSection.PHOTOS -> PhotosSection(info.photoUrls)
            PoiSection.REVIEWS -> ReviewsSection(info)
        }

        // Trademark attribution ("Google Maps" is non-translatable, see strings.xml).
        Text(
            stringResource(R.string.poi_google_attribution),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailsSection(info: GooglePoiInfo, showHours: Boolean) {
    // Gas stations only, and always exactly one grade — the response carries no
    // multi-grade table. `price` is Google's own formatting, currency symbol
    // included, and there is no currency code to re-format it against.
    info.fuelPrice?.let { fuel ->
        SectionHeader(stringResource(R.string.poi_fuel_price_header)) { IconFuelPrice() }
        Card {
            ListItem(
                { Text(fuel.price) },
                leadingContent = {},
                trailingContent = { Text(fuel.grade) },
                colors = ListItemDefaults.colors(Color.Transparent),
            )
        }
    }

    info.editorialSummary?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, maxLines = 3)
    }

    // Google's hours, which win when it has them (the OSM block is suppressed by
    // the caller in that case). This is today's line only — the search response
    // carries no weekly schedule — so it renders as however many lines came back
    // rather than collapsing to today behind a tap it no longer needs.
    if (showHours && info.hours.isNotEmpty()) {
        SectionHeader(stringResource(R.string.poi_hours_header)) { IconSchedule() }
        Card {
            Column {
                info.hours.forEach { line ->
                    val day = line.substringBefore(':', "").trim()
                    val hours = line.substringAfter(':', line).trim()
                    ListItem(
                        { Text(day.firstLetterUppercase()) },
                        leadingContent = {},
                        trailingContent = { Text(hours) },
                        colors = ListItemDefaults.colors(Color.Transparent),
                    )
                }
            }
        }
    }

    info.popularTimes?.let { PopularTimesSection(it) }
}

/**
 * The photo gallery, two to a row.
 *
 * A grid rather than the single [LazyRow] this replaces: on its own tab there is
 * width to spend and no competing section, and a row of one visible thumbnail was
 * most of what made the photos feel hidden.
 */
@Composable
private fun PhotosSection(photoUrls: List<String>) {
    photoUrls.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { url ->
                AsyncImage(
                    model = url,
                    contentDescription = stringResource(R.string.poi_photo_description),
                    modifier = Modifier
                        .weight(1f)
                        .height(120.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
            // Keeps a trailing odd photo half-width instead of stretching it across.
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ReviewsSection(info: GooglePoiInfo) {
    if (info.reviews.isNotEmpty()) {
        info.reviews.forEach { ReviewCard(it) }
    } else info.featuredReview?.let {
        Card {
            Text(
                "\u201C$it\u201D",
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                maxLines = 4,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        icon()
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ReviewCard(review: GoogleReview) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                review.authorPhoto?.let {
                    AsyncImage(
                        model = it,
                        contentDescription = stringResource(R.string.poi_reviewer_photo_description),
                        modifier = Modifier.size(32.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column {
                    Text(review.author, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    review.relativeTime?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (review.rating in 1..5) RatingStars(review.rating.toDouble())
            review.text?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 6)
            }
            if (review.photos.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(review.photos) { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = stringResource(R.string.poi_photo_description),
                            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PopularTimesSection(popularTimes: PoiPopularTimes) {
    // Show today's histogram (falling back to the first day Google returned).
    val todayDow = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.dayOfWeek.isoDayNumber
    val day = popularTimes.days.firstOrNull { it.dayOfWeek == todayDow } ?: popularTimes.days.firstOrNull() ?: return
    if (day.hours.isEmpty()) return
    SectionHeader(stringResource(R.string.poi_popular_times_header)) { IconInfo() }
    Card {
        Row(
            Modifier.fillMaxWidth().height(72.dp).padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            day.hours.forEach { h ->
                val frac = (h.occupancy.coerceIn(0, 100)) / 100f
                Box(
                    Modifier
                        .weight(1f)
                        .height((4 + (44 * frac)).dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

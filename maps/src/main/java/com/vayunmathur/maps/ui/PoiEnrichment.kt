package com.vayunmathur.maps.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.vayunmathur.library.ui.IconInfo
import com.vayunmathur.library.ui.IconPhotoLibrary
import com.vayunmathur.library.ui.IconSchedule
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.IconStarBorder
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.firstLetterUppercase
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.google.GooglePoiInfo
import com.vayunmathur.maps.data.google.GoogleReview
import com.vayunmathur.maps.data.google.PoiPopularTimes
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Renders the keyless Google Maps POI enrichment under a place's OSM details.
 *
 * Every section is conditional on its data being present — a keyless response is
 * bot-degraded (popular times and the full photo gallery are usually stripped)
 * and the scrape is fragile (a Google reshape nulls individual fields), so this
 * shows whatever came back and silently omits the rest. Nothing here can throw.
 *
 * Performance: photos are a horizontally-lazy [LazyRow] of async-loaded images;
 * reviews are capped to a handful (this sits inside the draggable bottom sheet,
 * which handles its own vertical scroll — no nested vertical LazyColumn).
 */
@Composable
fun GooglePoiEnrichment(info: GooglePoiInfo, hasOsmHours: Boolean, showSubtitle: Boolean = true) {
    if (info.isEmpty) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Price · category subtitle (rating itself is shown by the caller's header).
        // The reworked place sheet renders these in its own header, so it opts out.
        if (showSubtitle) {
            val subtitle = listOfNotNull(info.priceText?.ifBlank { null }, info.category?.ifBlank { null })
                .joinToString(" \u00B7 ")
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        info.editorialSummary?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, maxLines = 3)
        }

        // Google's weekly hours — only when OSM didn't already provide them, to
        // avoid showing the same schedule twice. Collapsed to TODAY's line by
        // default (tap to expand all 7 days) so the sheet stays short.
        if (!hasOsmHours && info.hours.isNotEmpty()) {
            var showAllHours by remember { mutableStateOf(false) }
            val todayName = Clock.System.now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date.dayOfWeek.name.lowercase().firstLetterUppercase()
            val todayLine = info.hours.firstOrNull {
                it.substringBefore(':', "").trim().equals(todayName, ignoreCase = true)
            } ?: info.hours.first()
            val shownHours = if (showAllHours) info.hours else listOf(todayLine)
            SectionHeader(stringResource(R.string.poi_hours_header)) { IconSchedule() }
            Card(modifier = Modifier.clickable { showAllHours = !showAllHours }) {
                Column {
                    shownHours.forEach { line ->
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

        if (info.photoUrls.isNotEmpty()) {
            SectionHeader(stringResource(R.string.poi_photos_header)) { IconPhotoLibrary() }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(info.photoUrls) { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = stringResource(R.string.poi_photo_description),
                        modifier = Modifier
                            .size(width = 160.dp, height = 120.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }

        info.popularTimes?.let { PopularTimesSection(it) }

        if (info.reviews.isNotEmpty()) {
            SectionHeader(stringResource(R.string.poi_reviews_header)) { IconStar() }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                info.reviews.take(5).forEach { ReviewCard(it) }
            }
        } else info.featuredReview?.let {
            SectionHeader(stringResource(R.string.poi_reviews_header)) { IconStar() }
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

        // Trademark attribution ("Google Maps" is non-translatable, see strings.xml).
        Text(
            stringResource(R.string.poi_google_attribution),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            if (review.rating in 1..5) RatingStars(review.rating)
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
private fun RatingStars(rating: Int) {
    Row {
        repeat(5) { i ->
            if (i < rating) IconStar(Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
            else IconStarBorder(Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

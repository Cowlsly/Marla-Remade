package com.vayunmathur.maps.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.ui.CategoryChips
import com.vayunmathur.library.ui.CompassCalibrationBanner
import com.vayunmathur.maps.ui.ContactAddressButton
import com.vayunmathur.maps.ui.MapCategory
import com.vayunmathur.maps.ui.VoiceSearchButton
import com.vayunmathur.maps.ui.theme.MapChromeMetrics
import com.vayunmathur.maps.R as MapsR

/**
 * The search field in the top app bar.
 *
 * Not editable: tapping it opens the search page, which owns the real field. It shows the
 * selected place's name when there is one, so the bar doubles as the "where you are looking"
 * label — which is why it is a read-only surface rather than a text field.
 */
@Composable
fun MapSearchBar(
    label: String,
    onOpenSearch: (query: String?) -> Unit,
    onContactAddress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(shape = MaterialTheme.shapes.extraLarge, modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onOpenSearch(null) }.padding(start = Spacing.md),
        ) {
            IconSearch(Modifier.size(20.dp))
            Text(
                label,
                modifier = Modifier.weight(1f).padding(horizontal = Spacing.sm),
                maxLines = 1,
            )
            // Contact address shortcut (P17/P31): pick a contact's postal address, geocode it
            // and open the resolved place directly — never through the search box, so no
            // query prefill and no results list.
            ContactAddressButton(onAddress = onContactAddress)
            // Voice search (P8): a transcript opens the search page pre-filled.
            VoiceSearchButton(onResult = { onOpenSearch(it) })
        }
    }
}

/**
 * The browse-mode row over the map: category chips, plus a compass hint when the heading is bad.
 *
 * Tapping a chip FILTERS the on-map POIs to that category's OSM types rather than running a text
 * search; tapping the active chip clears it.
 */
@Composable
fun MapBrowseHeader(
    selectedCategory: MapCategory?,
    onCategory: (MapCategory) -> Unit,
    headingAccuracy: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(MapChromeMetrics.chromeMargin).fillMaxWidth()) {
        CategoryChips(
            onCategory = onCategory,
            selected = selectedCategory,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.sm))
        // Only nag when the heading is genuinely unreliable; MEDIUM is good enough to draw a
        // puck with, and a banner that is always up is a banner nobody reads.
        if (headingAccuracy <= android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
            CompassCalibrationBanner(headingAccuracy)
        }
    }
}

/** The label the search bar shows: the selected place's name, else the placeholder. */
@Composable
fun mapSearchLabel(selectedName: String?): String =
    selectedName ?: stringResource(MapsR.string.search_placeholder)

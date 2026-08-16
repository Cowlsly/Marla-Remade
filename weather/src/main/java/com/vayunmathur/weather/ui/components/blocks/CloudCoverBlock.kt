package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.IconCloudy
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.vayunmathur.weather.R
import com.vayunmathur.weather.network.Current

/**
 * Cloud-cover percentage for the resolved period. Circular surface matching
 * the wind/pressure/visibility blocks, with a descriptive label
 * ("Clear" … "Overcast") under the value.
 */
@Composable
fun CloudCoverBlock(current: Current) {
    val pct = current.cloudCover.coerceIn(0, 100)
    val labelRes = when {
        pct < 10 -> R.string.cloud_clear
        pct < 40 -> R.string.cloud_mostly_clear
        pct < 70 -> R.string.cloud_partly_cloudy
        pct < 90 -> R.string.cloud_mostly_cloudy
        else -> R.string.cloud_overcast
    }
    CircularStatBlock {
        Box(Modifier.align(Alignment.TopCenter)) {
            BlockHeader(
                icon = { m, c -> IconCloudy(m, c) },
                title = stringResource(R.string.metric_cloud_cover),
                topPadding = 36.dp,
            )
        }
        Text(
            text = "$pct%",
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier.align(Alignment.Center).offset(y = 8.dp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(labelRes),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-26).dp)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            softWrap = true,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

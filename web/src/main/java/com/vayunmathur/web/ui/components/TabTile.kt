package com.vayunmathur.web.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.web.R
import com.vayunmathur.web.platform.BrowserTab
import com.vayunmathur.web.platform.BrowserUtils
import com.vayunmathur.web.platform.isNewTab

/**
 * One square in the tab switcher: a favicon-and-title header with the page preview below it.
 *
 * [thumbnail] is null far more often than it is not — a tab restored from a cold start has no
 * WebView to draw, a page rendering its content on the GPU captures blank so nothing is stored,
 * and the store-listing previews run with no device at all. So the empty state is the one this
 * has to look right in, not an afterthought.
 *
 * The preview is cropped from the top rather than fitted: a page is far taller than a square, and
 * scaling the whole thing down to fit would show a stripe of unreadable text floating in
 * letterboxing. The top of the page is the part the user recognises.
 *
 * With no preview the body is left as bare tint: the header already carries the favicon and title,
 * so drawing the icon again below it only says the same thing twice.
 *
 * [gestureModifier] goes on the content rather than on the card, and has to: on the `Main` pass the
 * inner node is offered each event first, so a gesture that claims the tile can consume the UP
 * before the card reads it as a tap.
 *
 * The close button is deeper again, which is what keeps it out of that gesture: it consumes the
 * DOWN before [gestureModifier] is offered it, so a press on the X cannot also pick the tile up.
 */
@Composable
fun TabTile(
    tab: BrowserTab,
    isActive: Boolean,
    thumbnail: Bitmap?,
    favicon: Bitmap?,
    onSwitch: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    gestureModifier: Modifier = Modifier,
) {
    val newTabLabel = stringResource(R.string.new_tab)
    val displayTitle = when {
        tab.isNewTab -> newTabLabel
        tab.title.isNotBlank() -> tab.title
        else -> BrowserUtils.prettyUrl(tab.url).ifBlank { newTabLabel }
    }
    Card(
        onClick = onSwitch,
        modifier = modifier.aspectRatio(1f),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
    ) {
        Column(Modifier.fillMaxSize().then(gestureModifier)) {
            // Height is left to the close button's touch target rather than fixed, so the
            // header cannot end up shorter than the thing it has to contain.
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SiteIcon(
                    icon = favicon,
                    label = displayTitle,
                    modifier = Modifier.size(18.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    textStyle = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.width(8.dp))
                if (tab.isPrivate) {
                    Text(stringResource(R.string.private_prefix), style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    text = displayTitle,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
                IconButton(onClick = onClose) {
                    IconClose(Modifier.size(18.dp))
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        alignment = Alignment.TopCenter,
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }
}

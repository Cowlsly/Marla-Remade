package com.vayunmathur.web.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import com.vayunmathur.library.ui.IconGlobe
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text

/**
 * A site's icon, with the initial-in-a-circle everything used before it as the fallback.
 *
 * Icons are only ever captured from pages the user has actually loaded, so a host the user has
 * not visited since the feature existed has none — and a tab restored from a cold start has none
 * either. Both are ordinary states rather than errors, which is why the fallback is part of this
 * composable instead of something the caller decides to draw.
 *
 * [label] is the text the initial is taken from, normally the title with the host behind it.
 */
@Composable
fun SiteIcon(
    icon: Bitmap?,
    label: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    textStyle: TextStyle = MaterialTheme.typography.titleSmall,
) {
    if (icon != null) {
        Image(
            bitmap = icon.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
        return
    }
    Surface(shape = CircleShape, color = containerColor, modifier = modifier) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val initial = label.trim().firstOrNull()?.uppercase()
            if (initial != null) Text(initial, style = textStyle) else IconGlobe(Modifier.fillMaxSize(0.6f))
        }
    }
}

package com.vayunmathur.games.alchemist.ui.components

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.vayunmathur.games.alchemist.R

@SuppressLint("DiscouragedApi")
@Composable
fun DynamicAlchemyIcon(iconId: Long, modifier: Modifier = Modifier, undiscovered: Boolean = false) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val name = "icon_${iconId.toString().padStart(3, '0')}"
    val resId = remember(iconId) {
        resources.getIdentifier(name, "drawable", context.packageName)
            .takeIf { it != 0 }
            ?: resources.getIdentifier(name, "drawable", "com.vayunmathur.games.alchemist")
    }

    if (resId != 0) {
        Box(modifier = modifier.fillMaxSize()) {
            Image(
                painter = painterResource(id = resId),
                contentDescription = stringResource(
                    R.string.alchemy_icon_content_description,
                    iconId
                ),
                modifier = modifier.fillMaxSize(),
                colorFilter = if (undiscovered) ColorFilter.tint(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                ) else null
            )
            if (undiscovered) {
                Text(
                    text = "?",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    } else {
        Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
            Text(
                text = "???",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

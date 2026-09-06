package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shown in place of a map whose renderer could not start.
 *
 * Pass this as `VectorMap`'s `fallback` slot:
 *
 * ```
 * VectorMap(cameraState = camera, fallback = { MapUnavailableMessage() })
 * ```
 *
 * It lives here rather than in `:library:map` because that module has no resources and
 * deliberately no material3 — giving it a `strings.xml` and hand-picked colours would look
 * wrong in six themes, and the first fix would be to add material3 to the one module whose
 * build file argues twice for a tight dependency graph.
 *
 * Deliberately takes no argument describing *why*. There are two failure reasons and they
 * are a developer distinction (a missing `.so` versus Vulkan refusing to initialise);
 * logcat under `MapRenderer` has the detail. Neither is actionable by the user, so both get
 * the same sentence.
 */
@Composable
fun MapUnavailableMessage(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconWarning(tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = stringResource(R.string.map_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

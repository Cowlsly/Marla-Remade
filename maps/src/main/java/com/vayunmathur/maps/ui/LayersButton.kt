package com.vayunmathur.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.library.ui.IconStyle
import com.vayunmathur.library.ui.SmallFloatingActionButton

/**
 * Minimal map-layers entry point in the browse FAB stack. For P2 this is just
 * the button surface (Vela's `LayersButton`); the full layer-toggle sheet
 * (satellite / traffic / transit) lands in P6, which wires [onClick] to open it.
 */
@Composable
fun LayersButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SmallFloatingActionButton(onClick = onClick, modifier = modifier) {
        IconStyle()
    }
}

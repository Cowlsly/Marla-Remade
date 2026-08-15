package com.vayunmathur.clock.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun KeypadRow(k1: String, k2: String, k3: String, onClick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        KeypadButton(k1, Modifier.weight(1f)) { onClick(k1) }
        KeypadButton(k2, Modifier.weight(1f)) { onClick(k2) }
        KeypadButton(k3, Modifier.weight(1f)) { onClick(k3) }
    }
}

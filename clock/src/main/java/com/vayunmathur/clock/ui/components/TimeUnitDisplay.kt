package com.vayunmathur.clock.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

@Composable
fun TimeUnitDisplay(value: String, unit: String, active: Boolean) {
    val color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.Bottom) {
        Text(text = value, style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp), color = color, fontWeight = FontWeight.Light)
        Text(text = unit, style = MaterialTheme.typography.titleMedium, color = color, modifier = Modifier.padding(bottom = 12.dp, start = 2.dp))
    }
}

package com.vayunmathur.games.logicgate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.logicgate.ui.Turing
import com.vayunmathur.library.ui.Text

@Composable
fun BitDotsRow(bits: List<Boolean>, dotSize: Dp = 14.dp, spacing: Dp = 4.dp, maxDots: Int = 8) {
    val display = if (bits.size > 1) bits.reversed() else bits
    Row(horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) {
        display.take(maxDots).forEach { b ->
            Box(modifier = Modifier.size(dotSize).clip(CircleShape).background(if (b) Turing.bitGreen else Turing.bitRed).border(0.8.dp, Color.Black.copy(alpha = 0.35f), CircleShape))
        }
        if (display.size > maxDots) Text("+${display.size - maxDots}", fontSize = 10.sp, color = Color(0xFF94A3B8))
    }
}

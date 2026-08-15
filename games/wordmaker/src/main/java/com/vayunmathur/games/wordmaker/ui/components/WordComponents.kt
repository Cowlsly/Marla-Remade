package com.vayunmathur.games.wordmaker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class AnimatedLetter(
    val char: Char,
    val startOffset: Offset,
    val endOffset: Offset,
    val progress: Animatable<Float, AnimationVector1D>
)

data class WordToAnimate(val word: String, val letterIds: List<Int>)


fun SurfaceText(modifier: Modifier, surfaceShape: Shape, surfaceColor: Color, text: String, textModifier: Modifier, fontWeight: FontWeight? = null, fontSize: TextUnit = TextUnit.Unspecified, surfaceSize: Dp?, textColor: Color = Color.Unspecified) {
    val modifier2 = if(surfaceSize != null) modifier.size(surfaceSize) else modifier
    Surface(modifier2, surfaceShape, surfaceColor) {
        Box(if(surfaceSize == null) Modifier else Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, textModifier, color = textColor, fontWeight = fontWeight, fontSize = fontSize)
        }
    }
}
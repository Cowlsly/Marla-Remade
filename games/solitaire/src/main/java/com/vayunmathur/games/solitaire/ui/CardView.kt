package com.vayunmathur.games.solitaire.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.CardColorScheme

val CARD_WIDTH = 60.dp
val CARD_HEIGHT = 84.dp

/**
 * The suit colouring every card face reads. Provided once around the whole app rather than
 * threaded through each board, because a card is drawn from six call sites that have no other
 * reason to know about settings.
 */
val LocalCardColorScheme: ProvidableCompositionLocal<CardColorScheme> =
    staticCompositionLocalOf { CardColorScheme.TWO_COLOUR }

@Composable
fun CardFace(card: Card, modifier: Modifier = Modifier, cardWidth: Dp = CARD_WIDTH, cardHeight: Dp = CARD_HEIGHT) {
    val color = LocalCardColorScheme.current.colorFor(card.suit)
    val scale = cardWidth.value / CARD_WIDTH.value
    val rankSize = (20 * scale).sp
    val suitSize = (24 * scale).sp
    val padSize = (4 * scale).dp
    val cornerSize = (8 * scale).dp

    Surface(
        modifier = modifier
            .width(cardWidth)
            .height(cardHeight),
        shape = RoundedCornerShape(cornerSize),
        color = Color.White,
        border = BorderStroke(1.dp, Color.Black)
    ) {
        Box(Modifier.padding(padSize)) {
            Text(
                text = card.rank.display,
                color = color,
                fontSize = rankSize,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart)
            )
            Text(
                text = card.suit.symbol,
                color = color,
                fontSize = suitSize,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
fun CardBack(modifier: Modifier = Modifier, cardWidth: Dp = CARD_WIDTH, cardHeight: Dp = CARD_HEIGHT) {
    val scale = cardWidth.value / CARD_WIDTH.value
    val cornerSize = (8 * scale).dp
    Surface(
        modifier = modifier
            .width(cardWidth)
            .height(cardHeight),
        shape = RoundedCornerShape(cornerSize),
        color = Color(0xFF1565C0),
        border = BorderStroke(1.dp, Color.Black)
    ) {
        Box(
            Modifier
                .padding((4 * scale).dp)
                .background(Color(0xFF0D47A1), RoundedCornerShape((4 * scale).dp))
        )
    }
}

@Composable
fun EmptySlot(
    modifier: Modifier = Modifier,
    label: String = "",
    labelColor: Color = Color.Gray,
    cardWidth: Dp = CARD_WIDTH,
    cardHeight: Dp = CARD_HEIGHT
) {
    val scale = cardWidth.value / CARD_WIDTH.value
    val cornerSize = (8 * scale).dp
    val dashedStroke = Stroke(
        width = 2f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
    )
    Box(
        modifier = modifier
            .width(cardWidth)
            .height(cardHeight)
            .drawBehind {
                drawRoundRect(
                    color = Color.Gray,
                    style = dashedStroke,
                    cornerRadius = CornerRadius(cornerSize.toPx())
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (label.isNotEmpty()) {
            Text(label, color = labelColor, fontSize = (16 * scale).sp)
        }
    }
}

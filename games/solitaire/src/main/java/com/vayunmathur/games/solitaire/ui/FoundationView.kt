package com.vayunmathur.games.solitaire.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.Suit
import com.vayunmathur.games.solitaire.platform.SolitaireActions

@Composable
fun FoundationSlot(
    cards: List<Card>,
    index: Int,
    actions: SolitaireActions,
    modifier: Modifier = Modifier,
    cardWidth: Dp = CARD_WIDTH,
    cardHeight: Dp = CARD_HEIGHT
) {
    DropTarget("foundation_$index", actions, modifier) {
        if (cards.isNotEmpty()) {
            CardFace(cards.last(), cardWidth = cardWidth, cardHeight = cardHeight)
        } else {
            val suit = Suit.entries.getOrNull(index)
            EmptySlot(
                label = suit?.symbol ?: "",
                labelColor = suit?.let { LocalCardColorScheme.current.colorFor(it).copy(alpha = 0.5f) }
                    ?: Color.Gray,
                cardWidth = cardWidth,
                cardHeight = cardHeight
            )
        }
    }
}

@Composable
fun FreeCellSlot(
    card: Card?,
    index: Int,
    actions: SolitaireActions,
    modifier: Modifier = Modifier,
    cardWidth: Dp = CARD_WIDTH,
    cardHeight: Dp = CARD_HEIGHT
) {
    DropTarget("freecell_$index", actions, modifier) {
        if (card != null) {
            DraggableCard(
                card = card,
                sourceId = "freecell_$index",
                actions = actions,
                cardWidth = cardWidth,
                cardHeight = cardHeight
            ) {
                CardFace(card, cardWidth = cardWidth, cardHeight = cardHeight)
            }
        } else {
            EmptySlot(cardWidth = cardWidth, cardHeight = cardHeight)
        }
    }
}


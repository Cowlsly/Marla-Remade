package com.vayunmathur.games.solitaire.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
            val hint = Suit.entries.getOrNull(index)?.symbol ?: ""
            EmptySlot(label = hint, cardWidth = cardWidth, cardHeight = cardHeight)
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


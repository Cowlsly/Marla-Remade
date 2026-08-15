package com.vayunmathur.games.solitaire.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.TableauPile
import com.vayunmathur.games.solitaire.platform.SolitaireActions

private val FACE_DOWN_OVERLAP = 8.dp
private val FACE_UP_OVERLAP = 22.dp

@Composable
fun TableauColumn(
    pile: TableauPile,
    columnIndex: Int,
    actions: SolitaireActions,
    modifier: Modifier = Modifier,
    cardWidth: Dp = CARD_WIDTH,
    cardHeight: Dp = CARD_HEIGHT
) {
    val totalHeight = if (pile.faceDown.isEmpty() && pile.faceUp.isEmpty()) {
        cardHeight
    } else {
        FACE_DOWN_OVERLAP * pile.faceDown.size +
        FACE_UP_OVERLAP * maxOf(0, pile.faceUp.size - 1) +
        cardHeight
    }

    DropTarget("tableau_$columnIndex", actions, modifier) {
        Box(modifier = Modifier.heightIn(min = totalHeight)) {
            if (pile.faceDown.isEmpty() && pile.faceUp.isEmpty()) {
                EmptySlot(cardWidth = cardWidth, cardHeight = cardHeight)
            }
            pile.faceDown.indices.forEach { i ->
                CardBack(
                    modifier = Modifier.offset(y = FACE_DOWN_OVERLAP * i),
                    cardWidth = cardWidth,
                    cardHeight = cardHeight
                )
            }
            val faceDownOffset = FACE_DOWN_OVERLAP * pile.faceDown.size
            pile.faceUp.forEachIndexed { index, card ->
                DraggableCard(
                    card = card,
                    sourceId = "tableau_${columnIndex}_$index",
                    actions = actions,
                    modifier = Modifier.offset(y = faceDownOffset + FACE_UP_OVERLAP * index),
                    cardWidth = cardWidth,
                    cardHeight = cardHeight
                ) {
                    CardFace(
                        card = card,
                        cardWidth = cardWidth,
                        cardHeight = cardHeight
                    )
                }
            }
        }
    }
}

@Composable
fun FreeCellTableauColumn(
    pile: List<Card>,
    columnIndex: Int,
    actions: SolitaireActions,
    modifier: Modifier = Modifier,
    cardWidth: Dp = CARD_WIDTH,
    cardHeight: Dp = CARD_HEIGHT
) {
    val totalHeight = if (pile.isEmpty()) {
        cardHeight
    } else {
        FACE_UP_OVERLAP * maxOf(0, pile.size - 1) + cardHeight
    }

    DropTarget("tableau_$columnIndex", actions, modifier) {
        Box(modifier = Modifier.heightIn(min = totalHeight)) {
            if (pile.isEmpty()) {
                EmptySlot(cardWidth = cardWidth, cardHeight = cardHeight)
            }
            pile.forEachIndexed { index, card ->
                DraggableCard(
                    card = card,
                    sourceId = "tableau_${columnIndex}_$index",
                    actions = actions,
                    modifier = Modifier.offset(y = FACE_UP_OVERLAP * index),
                    cardWidth = cardWidth,
                    cardHeight = cardHeight
                ) {
                    CardFace(
                        card = card,
                        cardWidth = cardWidth,
                        cardHeight = cardHeight
                    )
                }
            }
        }
    }
}


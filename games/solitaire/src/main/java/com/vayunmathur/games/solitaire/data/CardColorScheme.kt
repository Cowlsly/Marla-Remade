package com.vayunmathur.games.solitaire.data

import androidx.compose.ui.graphics.Color

/**
 * How suits are coloured on the card faces.
 *
 * [TWO_COLOUR] is the traditional deck: red hearts and diamonds against black spades and clubs.
 * That leaves the two reds and the two blacks separated only by the pip shape, which is what
 * makes a board unreadable for a player with a red/green or contrast deficiency (#566).
 * [FOUR_COLOUR] gives every suit its own hue — the standard accessible scheme of black spades,
 * red hearts, blue diamonds and green clubs — so suit is legible from colour alone.
 *
 * The colours are fixed rather than taken from the Material You palette: they have to stay
 * distinguishable from each other on a white card, which a wallpaper-derived scheme cannot
 * promise.
 */
enum class CardColorScheme {
    TWO_COLOUR,
    FOUR_COLOUR;

    fun colorFor(suit: Suit): Color = when (this) {
        TWO_COLOUR -> if (suit.isRed) Red else Black
        FOUR_COLOUR -> when (suit) {
            Suit.HEARTS -> Red
            Suit.DIAMONDS -> Blue
            Suit.CLUBS -> Green
            Suit.SPADES -> Black
        }
    }

    private companion object {
        val Red = Color(0xFFCC0000)
        val Black = Color(0xFF000000)
        val Blue = Color(0xFF0B57D0)
        val Green = Color(0xFF1B7F3B)
    }
}

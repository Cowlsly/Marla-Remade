package com.vayunmathur.games.solitaire

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.DrawMode
import com.vayunmathur.games.solitaire.data.FreeCellState
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.KlondikeDifficulty
import com.vayunmathur.games.solitaire.data.KlondikeState
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.SolitaireUiState
import com.vayunmathur.games.solitaire.data.SpiderState
import com.vayunmathur.games.solitaire.data.Suit
import com.vayunmathur.games.solitaire.data.TableauPile
import com.vayunmathur.games.solitaire.platform.SolitaireActions
import com.vayunmathur.library.ui.DynamicTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:games:solitaire`. See `common-conventions-preview-metadata`.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 *
 * The deals below are written out by hand rather than dealt from a shuffled deck, so the
 * images are reproducible from a clean checkout instead of depending on a shuffle seed. The
 * Klondike and FreeCell boards are complete, legal 52-card deals; Spider's face-down cards
 * are filler, since a 104-card deck with duplicates shows nothing but card backs there.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-klondike", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Klondike() {
        val klondike = KlondikeState(
            stock = cards("9S 5C 3S 8C AC 6S 2C 10S KC JS 4C 7C 5S 3C 9C"),
            waste = cards("AS 2S 7S"),
            tableauPiles = listOf(
                TableauPile(faceUp = cards("KS QH JC")),
                TableauPile(cards("3H"), cards("9D 8S")),
                TableauPile(cards("4H"), cards("10H")),
                TableauPile(cards("6H 8H 9H"), cards("QS JD 10C")),
                TableauPile(cards("JH KH"), cards("7H 6C 5H")),
                TableauPile(cards("2D 3D 4D 5D 6D"), cards("4S")),
                TableauPile(cards("7D 8D 10D QD"), cards("KD QC")),
            ),
            foundations = listOf(cards("AH 2H"), cards("AD"), emptyList(), emptyList()),
            drawMode = DrawMode.DRAW_ONE,
            difficulty = KlondikeDifficulty.REGULAR,
            redealsRemaining = 2,
            moveCount = 24,
            elapsedSeconds = 143,
        )
        DynamicTheme(darkTheme = true) {
            GameBoardScreen(
                state = SolitaireUiState(
                    gameMode = GameMode.KLONDIKE,
                    klondike = klondike,
                    history = listOf(klondike),
                ),
                mode = GameMode.KLONDIKE,
                actions = SolitaireActions.Noop,
                onExit = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-spider", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Spider() {
        val spider = SpiderState(
            tableauPiles = listOf(
                TableauPile(faceDown(5), cards("9H 8H")),
                TableauPile(faceDown(5), cards("KS QS JS")),
                TableauPile(faceDown(4), cards("7D")),
                TableauPile(faceDown(5), cards("10C 9C")),
                TableauPile(faceDown(4), cards("4H 3S")),
                TableauPile(faceDown(5), cards("QD JD 10D")),
                TableauPile(faceDown(4), cards("6S 5H")),
                TableauPile(faceDown(4), cards("AC")),
                TableauPile(faceDown(5), cards("8S 7S 6S")),
                TableauPile(faceDown(4), cards("KH QC")),
            ),
            // Two of the five deals have been used, and one suit is already home.
            stockGroups = List(3) { cards("2C 3C 4C 5C 6C 7C 8C 9C 10C JC") },
            suitCount = 4,
            completedSuits = 1,
            moveCount = 61,
            elapsedSeconds = 372,
        )
        DynamicTheme(darkTheme = true) {
            GameBoardScreen(
                state = SolitaireUiState(
                    gameMode = GameMode.SPIDER,
                    spider = spider,
                    history = listOf(spider),
                ),
                mode = GameMode.SPIDER,
                actions = SolitaireActions.Noop,
                onExit = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-freecell", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3FreeCell() {
        val freeCell = FreeCellState(
            tableauPiles = listOf(
                cards("9S 3C QC QS 5S 7H"),
                cards("4H QH 9H 8D 10D 5C"),
                cards("JC 5D 10S JH 3S QD"),
                cards("6D 8C 5H 2S 9D AC"),
                cards("2C JS 9C 7C 6C"),
                cards("10H 6H 3D 6S KH"),
                cards("KS 10C KC 4C 4S"),
                cards("7D 4D 8H JD 8S"),
            ),
            freeCells = listOf(card("KD"), null, card("7S"), null),
            foundations = listOf(cards("AH 2H 3H"), cards("AD 2D"), cards("AS"), emptyList()),
            moveCount = 37,
            elapsedSeconds = 208,
        )
        DynamicTheme(darkTheme = true) {
            GameBoardScreen(
                state = SolitaireUiState(
                    gameMode = GameMode.FREECELL,
                    freeCell = freeCell,
                    history = listOf(freeCell),
                ),
                mode = GameMode.FREECELL,
                actions = SolitaireActions.Noop,
                onExit = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-win", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview4Win() {
        DynamicTheme(darkTheme = true) {
            GameBoardScreen(
                state = SolitaireUiState(
                    gameMode = GameMode.KLONDIKE,
                    klondike = KlondikeState(
                        // Every suit home, tableau cleared: the position that wins Klondike.
                        foundations = Suit.entries.map { suit -> Rank.entries.map { Card(suit, it) } },
                        moveCount = 118,
                        elapsedSeconds = 254,
                        isWon = true,
                    ),
                ),
                mode = GameMode.KLONDIKE,
                actions = SolitaireActions.Noop,
                onExit = {},
            )
        }
    }

    /** `"KS QH 10C"` — so a dealt board reads as a hand rather than a wall of enum pairs. */
    private fun cards(spec: String): List<Card> = spec.split(' ').map { token ->
        val suit = when (token.last()) {
            'H' -> Suit.HEARTS
            'D' -> Suit.DIAMONDS
            'S' -> Suit.SPADES
            else -> Suit.CLUBS
        }
        Card(suit, Rank.entries.first { it.display == token.dropLast(1) })
    }

    private fun card(spec: String): Card = cards(spec).single()

    /** Face-down cards are drawn as backs, so only the count of them is ever visible. */
    private fun faceDown(count: Int): List<Card> =
        List(count) { Card(Suit.SPADES, Rank.entries[it % Rank.entries.size]) }
}


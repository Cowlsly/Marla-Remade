package com.vayunmathur.games.solitaire.platform

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import com.vayunmathur.games.solitaire.data.*
import com.vayunmathur.library.util.AchievementsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DragInfo(
    val cards: List<Card>,
    val sourceId: String,
    val offset: Offset = Offset.Zero,
    val startPos: Offset = Offset.Zero,
    val cardSize: androidx.compose.ui.unit.IntSize = androidx.compose.ui.unit.IntSize.Zero
)

private fun overlapArea(a: Rect, b: Rect): Float {
    val left = maxOf(a.left, b.left)
    val right = minOf(a.right, b.right)
    val top = maxOf(a.top, b.top)
    val bottom = minOf(a.bottom, b.bottom)
    if (left >= right || top >= bottom) return 0f
    return (right - left) * (bottom - top)
}

class SolitaireViewModel(application: Application) : AndroidViewModel(application), SolitaireActions {
    private val _uiState = MutableStateFlow(SolitaireUiState())
    val uiState: StateFlow<SolitaireUiState> = _uiState.asStateFlow()

    private val _dragInfo = MutableStateFlow<DragInfo?>(null)
    override val dragInfo: StateFlow<DragInfo?> = _dragInfo.asStateFlow()

    private val statsRepository = SolitaireStatsRepository(application)
    override val dropTargets: MutableMap<String, Rect> = mutableMapOf()

    val achievementsManager: AchievementsManager = run {
        val json = application.assets.open("achievements.json")
            .bufferedReader().readText()
        SolitaireAchievementsManager(application, json, statsRepository)
    }

    init {
        achievementsManager.checkExistingAchievements()
    }

    fun getStats(mode: GameMode): GameStats = statsRepository.getModeStats(mode)

    fun hasActiveGame(): Boolean = with(_uiState.value) {
        when (gameMode) {
            GameMode.KLONDIKE -> klondike?.isWon == false
            GameMode.SPIDER -> spider?.isWon == false
            GameMode.FREECELL -> freeCell?.isWon == false
            GameMode.PYRAMID -> pyramid?.isWon == false
            null -> false
        }
    }

    fun selectMode(mode: GameMode, config: GameConfig = GameConfig()) {
        when (mode) {
            GameMode.KLONDIKE -> newKlondikeGame(config)
            GameMode.SPIDER -> newSpiderGame(config)
            GameMode.FREECELL -> newFreeCellGame()
            GameMode.PYRAMID -> newPyramidGame(config)
        }
    }

    /** The config of the currently active game, for restart / play-again. */
    fun currentConfig(): GameConfig = with(_uiState.value) {
        when (gameMode) {
            GameMode.KLONDIKE -> klondike?.let { GameConfig(drawMode = it.drawMode, klondikeDifficulty = it.difficulty) }
            GameMode.SPIDER -> spider?.let { GameConfig(spiderSuits = it.suitCount) }
            GameMode.PYRAMID -> pyramid?.let { GameConfig(relaxed = it.relaxed) }
            else -> null
        } ?: GameConfig()
    }

    private fun currentVariant(): String = with(_uiState.value) {
        when (gameMode) {
            GameMode.KLONDIKE -> klondike?.variant
            GameMode.SPIDER -> spider?.variant
            GameMode.FREECELL -> freeCell?.variant
            GameMode.PYRAMID -> pyramid?.variant
            null -> null
        } ?: ""
    }

    override fun giveUp() {
        val mode = _uiState.value.gameMode ?: return
        statsRepository.recordGameLost(mode, currentVariant())
        _uiState.value = SolitaireUiState()
    }

    // --- Klondike ---

    fun newKlondikeGame(config: GameConfig) {
        val deck = createShuffledDeck()
        val tableauPiles = mutableListOf<TableauPile>()
        var index = 0
        for (i in 0 until 7) {
            val faceDown = deck.subList(index, index + i)
            index += i
            val faceUp = listOf(deck[index])
            index++
            tableauPiles.add(TableauPile(faceDown, faceUp))
        }
        val stock = deck.subList(index, deck.size)
        val variant = "${config.drawMode.name}_${config.klondikeDifficulty.name}"
        _uiState.value = SolitaireUiState(
            gameMode = GameMode.KLONDIKE,
            klondike = KlondikeState(
                stock = stock,
                waste = emptyList(),
                tableauPiles = tableauPiles,
                foundations = List(4) { emptyList() },
                drawMode = config.drawMode,
                difficulty = config.klondikeDifficulty,
                redealsRemaining = config.klondikeDifficulty.redeals(),
                variant = variant
            ),
            history = emptyList()
        )
        statsRepository.recordGamePlayed(GameMode.KLONDIKE, variant)
    }

    override fun drawFromStock() {
        val state = _uiState.value.klondike ?: return
        if (state.isWon) return
        if (state.stock.isEmpty() && state.waste.isEmpty()) return
        if (state.stock.isEmpty()) {
            // Recycle the waste into the stock. Redeals are limited by difficulty
            // (Hard = 0, Regular = 2, Relaxed = unlimited).
            if (state.redealsRemaining <= 0) return
            saveHistory()
            _uiState.update {
                it.copy(klondike = state.copy(
                    stock = state.waste.reversed(),
                    waste = emptyList(),
                    redealsRemaining = if (state.redealsRemaining == Int.MAX_VALUE) Int.MAX_VALUE else state.redealsRemaining - 1,
                    moveCount = state.moveCount + 1
                ))
            }
        } else {
            saveHistory()
        val drawCount = if (state.drawMode == DrawMode.DRAW_THREE) 3 else 1
            val drawn = state.stock.takeLast(drawCount).reversed()
            _uiState.update {
                it.copy(klondike = state.copy(
                    stock = state.stock.dropLast(drawCount),
                    waste = state.waste + drawn,
                    moveCount = state.moveCount + 1
                ))
            }
        }
    }

    fun klondikeMoveWasteToTableau(columnIndex: Int) {
        val state = _uiState.value.klondike ?: return
        if (state.isWon || state.waste.isEmpty()) return
        val card = state.waste.last()
        val pile = state.tableauPiles[columnIndex]
        if (!canPlaceOnKlondikeTableau(card, pile)) return
        saveHistory()
        val newPiles = state.tableauPiles.toMutableList()
        newPiles[columnIndex] = pile.copy(faceUp = pile.faceUp + card)
        _uiState.update {
            it.copy(klondike = state.copy(
                waste = state.waste.dropLast(1),
                tableauPiles = newPiles,
                moveCount = state.moveCount + 1
            ))
        }
        checkKlondikeWin()
    }

    fun klondikeMoveWasteToFoundation(foundationIndex: Int) {
        val state = _uiState.value.klondike ?: return
        if (state.isWon || state.waste.isEmpty()) return
        val card = state.waste.last()
        if (!canPlaceOnFoundation(card, state.foundations[foundationIndex], foundationIndex)) return
        saveHistory()
        val newFoundations = state.foundations.toMutableList()
        newFoundations[foundationIndex] = newFoundations[foundationIndex] + card
        _uiState.update {
            it.copy(klondike = state.copy(
                waste = state.waste.dropLast(1),
                foundations = newFoundations,
                moveCount = state.moveCount + 1
            ))
        }
        checkKlondikeWin()
    }

    fun klondikeMoveTableauToFoundation(fromColumn: Int, foundationIndex: Int) {
        val state = _uiState.value.klondike ?: return
        if (state.isWon) return
        val pile = state.tableauPiles[fromColumn]
        if (pile.faceUp.isEmpty()) return
        val card = pile.faceUp.last()
        if (!canPlaceOnFoundation(card, state.foundations[foundationIndex], foundationIndex)) return
        saveHistory()
        val newPiles = state.tableauPiles.toMutableList()
        val newFaceUp = pile.faceUp.dropLast(1)
        newPiles[fromColumn] = autoFlip(pile.copy(faceUp = newFaceUp))
        val newFoundations = state.foundations.toMutableList()
        newFoundations[foundationIndex] = newFoundations[foundationIndex] + card
        _uiState.update {
            it.copy(klondike = state.copy(
                tableauPiles = newPiles,
                foundations = newFoundations,
                moveCount = state.moveCount + 1
            ))
        }
        checkKlondikeWin()
    }

    fun klondikeMoveTableauToTableau(fromColumn: Int, cardIndex: Int, toColumn: Int) {
        val state = _uiState.value.klondike ?: return
        if (state.isWon) return
        val fromPile = state.tableauPiles[fromColumn]
        if (cardIndex < 0 || cardIndex >= fromPile.faceUp.size) return
        val movingCards = fromPile.faceUp.subList(cardIndex, fromPile.faceUp.size)
        val topCard = movingCards.first()
        val toPile = state.tableauPiles[toColumn]
        if (!canPlaceOnKlondikeTableau(topCard, toPile)) return
        saveHistory()
        val newPiles = state.tableauPiles.toMutableList()
        newPiles[fromColumn] = autoFlip(fromPile.copy(faceUp = fromPile.faceUp.subList(0, cardIndex)))
        newPiles[toColumn] = toPile.copy(faceUp = toPile.faceUp + movingCards)
        _uiState.update {
            it.copy(klondike = state.copy(
                tableauPiles = newPiles,
                moveCount = state.moveCount + 1
            ))
        }
    }

    override fun klondikeAutoComplete() {
        val state = _uiState.value.klondike ?: return
        if (state.isWon) return
        if (state.tableauPiles.any { it.faceDown.isNotEmpty() }) return
        saveHistory()
        var current = state
        var madeProgress = true
        while (madeProgress) {
            madeProgress = false
            // Move as many waste/tableau cards to foundations as possible
            var moved = true
            while (moved) {
                moved = false
                if (current.waste.isNotEmpty()) {
                    val card = current.waste.last()
                    for (fi in current.foundations.indices) {
                        if (canPlaceOnFoundation(card, current.foundations[fi], fi)) {
                            val newFoundations = current.foundations.toMutableList()
                            newFoundations[fi] = newFoundations[fi] + card
                            current = current.copy(
                                waste = current.waste.dropLast(1),
                                foundations = newFoundations,
                                moveCount = current.moveCount + 1
                            )
                            moved = true
                            madeProgress = true
                            break
                        }
                    }
                    if (moved) continue
                }
                for (i in current.tableauPiles.indices) {
                    val pile = current.tableauPiles[i]
                    if (pile.faceUp.isNotEmpty()) {
                        val card = pile.faceUp.last()
                        for (fi in current.foundations.indices) {
                            if (canPlaceOnFoundation(card, current.foundations[fi], fi)) {
                                val newFoundations = current.foundations.toMutableList()
                                newFoundations[fi] = newFoundations[fi] + card
                                val newPiles = current.tableauPiles.toMutableList()
                                newPiles[i] = autoFlip(pile.copy(faceUp = pile.faceUp.dropLast(1)))
                                current = current.copy(
                                    tableauPiles = newPiles,
                                    foundations = newFoundations,
                                    moveCount = current.moveCount + 1
                                )
                                moved = true
                                madeProgress = true
                                break
                            }
                        }
                        if (moved) break
                    }
                }
            }
            // Draw one stock card and try again
            if (current.stock.isNotEmpty()) {
                current = current.copy(
                    stock = current.stock.dropLast(1),
                    waste = current.waste + current.stock.last()
                )
                madeProgress = true
            }
        }
        _uiState.update { it.copy(klondike = current) }
        checkKlondikeWin()
    }

    private fun canPlaceOnKlondikeTableau(card: Card, pile: TableauPile): Boolean = when {
        pile.faceUp.isEmpty() && pile.faceDown.isEmpty() -> card.rank == Rank.KING
        pile.faceUp.isEmpty() -> false
        else -> pile.faceUp.last().isOneHigherThan(card) && card.alternatesColorWith(pile.faceUp.last())
    }

    private fun canPlaceOnFoundation(card: Card, foundation: List<Card>, foundationIndex: Int): Boolean =
        if (foundation.isEmpty()) card.rank == Rank.ACE && card.suit == Suit.entries[foundationIndex]
        else card.suit == foundation.last().suit && card.isOneHigherThan(foundation.last())

    private fun autoFlip(pile: TableauPile): TableauPile =
        if (pile.faceUp.isEmpty() && pile.faceDown.isNotEmpty())
            pile.copy(faceDown = pile.faceDown.dropLast(1), faceUp = listOf(pile.faceDown.last()))
        else pile

    private fun checkKlondikeWin() {
        val state = _uiState.value.klondike ?: return
        if (state.foundations.all { it.size == 13 }) {
            _uiState.update { it.copy(klondike = state.copy(isWon = true)) }
            onGameWon(GameMode.KLONDIKE, state.elapsedSeconds, state.moveCount, state.usedUndo)
        }
    }

    // --- Spider ---

    fun newSpiderGame(config: GameConfig) {
        val suitCount = config.spiderSuits
        val deck = createSpiderDeck(suitCount)
        val tableauPiles = mutableListOf<TableauPile>()
        var index = 0
        for (i in 0 until 10) {
            val count = if (i < 4) 6 else 5
            val faceDown = deck.subList(index, index + count - 1)
            index += count - 1
            val faceUp = listOf(deck[index])
            index++
            tableauPiles.add(TableauPile(faceDown, faceUp))
        }
        val remaining = deck.subList(index, deck.size)
        val stockGroups = remaining.chunked(10)
        val variant = "${suitCount}SUIT"
        _uiState.value = SolitaireUiState(
            gameMode = GameMode.SPIDER,
            spider = SpiderState(
                tableauPiles = tableauPiles,
                stockGroups = stockGroups,
                suitCount = suitCount,
                variant = variant
            ),
            history = emptyList()
        )
        statsRepository.recordGamePlayed(GameMode.SPIDER, variant)
    }

    override fun dealSpiderStock() {
        val state = _uiState.value.spider ?: return
        if (state.isWon || state.stockGroups.isEmpty()) return
        if (state.tableauPiles.any { it.faceUp.isEmpty() && it.faceDown.isEmpty() }) return
        saveHistory()
        val group = state.stockGroups.first()
        val newPiles = state.tableauPiles.toMutableList()
        for (i in 0 until minOf(group.size, 10)) {
            val pile = newPiles[i]
            newPiles[i] = pile.copy(faceUp = pile.faceUp + group[i])
        }
        _uiState.update {
            it.copy(spider = state.copy(
                tableauPiles = newPiles,
                stockGroups = state.stockGroups.drop(1),
                moveCount = state.moveCount + 1
            ))
        }
        checkSpiderCompletedSuits()
    }

    fun spiderMoveCards(fromColumn: Int, cardIndex: Int, toColumn: Int) {
        val state = _uiState.value.spider ?: return
        if (state.isWon) return
        val fromPile = state.tableauPiles[fromColumn]
        if (cardIndex < 0 || cardIndex >= fromPile.faceUp.size) return
        val movingCards = fromPile.faceUp.subList(cardIndex, fromPile.faceUp.size)
        if (!isSpiderSequence(movingCards)) return
        val toPile = state.tableauPiles[toColumn]
        if (!canPlaceOnSpiderTableau(movingCards.first(), toPile)) return
        saveHistory()
        val newPiles = state.tableauPiles.toMutableList()
        newPiles[fromColumn] = autoFlip(fromPile.copy(faceUp = fromPile.faceUp.subList(0, cardIndex)))
        newPiles[toColumn] = toPile.copy(faceUp = toPile.faceUp + movingCards)
        _uiState.update {
            it.copy(spider = state.copy(
                tableauPiles = newPiles,
                moveCount = state.moveCount + 1
            ))
        }
        checkSpiderCompletedSuits()
    }

    private fun isSpiderSequence(cards: List<Card>): Boolean =
        cards.zipWithNext().all { (a, b) -> a.suit == b.suit && a.isOneHigherThan(b) }

    private fun canPlaceOnSpiderTableau(card: Card, pile: TableauPile): Boolean = when {
        pile.faceUp.isEmpty() && pile.faceDown.isEmpty() -> true
        pile.faceUp.isEmpty() -> false
        else -> pile.faceUp.last().rank.value == card.rank.value + 1
    }

    private fun checkSpiderCompletedSuits() {
        val state = _uiState.value.spider ?: return
        val newPiles = state.tableauPiles.toMutableList()
        var completed = state.completedSuits
        for (i in newPiles.indices) {
            val pile = newPiles[i]
            if (pile.faceUp.size >= 13) {
                val last13 = pile.faceUp.takeLast(13)
                if (isSpiderSequence(last13) && last13.first().rank == Rank.KING && last13.last().rank == Rank.ACE) {
                    newPiles[i] = autoFlip(pile.copy(faceUp = pile.faceUp.dropLast(13)))
                    completed++
                }
            }
        }
        if (completed != state.completedSuits) {
            val newState = state.copy(tableauPiles = newPiles, completedSuits = completed, isWon = completed >= 8)
            _uiState.update { it.copy(spider = newState) }
            if (newState.isWon) {
                onGameWon(GameMode.SPIDER, state.elapsedSeconds, state.moveCount, state.usedUndo)
            }
        }
    }

    // --- FreeCell ---

    fun newFreeCellGame() {
        val deck = createShuffledDeck()
        val piles = List(8) { mutableListOf<Card>() }
        for (i in deck.indices) {
            piles[i % 8].add(deck[i])
        }
        _uiState.value = SolitaireUiState(
            gameMode = GameMode.FREECELL,
            freeCell = FreeCellState(
                tableauPiles = piles.map { it.toList() },
                freeCells = List(4) { null },
                foundations = List(4) { emptyList() },
                variant = "STANDARD"
            ),
            history = emptyList()
        )
        statsRepository.recordGamePlayed(GameMode.FREECELL, "STANDARD")
    }

    fun freeCellMoveToFreeCell(fromColumn: Int, cellIndex: Int) {
        val state = _uiState.value.freeCell ?: return
        if (state.isWon) return
        val pile = state.tableauPiles[fromColumn]
        if (pile.isEmpty()) return
        if (state.freeCells[cellIndex] != null) return
        saveHistory()
        val card = pile.last()
        val newPiles = state.tableauPiles.toMutableList()
        newPiles[fromColumn] = pile.dropLast(1)
        val newCells = state.freeCells.toMutableList()
        newCells[cellIndex] = card
        _uiState.update {
            it.copy(freeCell = state.copy(
                tableauPiles = newPiles,
                freeCells = newCells,
                moveCount = state.moveCount + 1
            ))
        }
    }

    fun freeCellMoveFromFreeCell(cellIndex: Int, toColumn: Int) {
        val state = _uiState.value.freeCell ?: return
        if (state.isWon) return
        val card = state.freeCells[cellIndex] ?: return
        val pile = state.tableauPiles[toColumn]
        if (!canPlaceOnFreeCellTableau(card, pile)) return
        saveHistory()
        val newPiles = state.tableauPiles.toMutableList()
        newPiles[toColumn] = pile + card
        val newCells = state.freeCells.toMutableList()
        newCells[cellIndex] = null
        _uiState.update {
            it.copy(freeCell = state.copy(
                tableauPiles = newPiles,
                freeCells = newCells,
                moveCount = state.moveCount + 1
            ))
        }
    }

    fun freeCellMoveFreeCellToFoundation(cellIndex: Int, foundationIndex: Int) {
        val state = _uiState.value.freeCell ?: return
        if (state.isWon) return
        val card = state.freeCells[cellIndex] ?: return
        if (!canPlaceOnFoundation(card, state.foundations[foundationIndex], foundationIndex)) return
        saveHistory()
        val newCells = state.freeCells.toMutableList()
        newCells[cellIndex] = null
        val newFoundations = state.foundations.toMutableList()
        newFoundations[foundationIndex] = newFoundations[foundationIndex] + card
        _uiState.update {
            it.copy(freeCell = state.copy(
                freeCells = newCells,
                foundations = newFoundations,
                moveCount = state.moveCount + 1
            ))
        }
        checkFreeCellWin()
    }

    fun freeCellMoveTableauToFoundation(fromColumn: Int, foundationIndex: Int) {
        val state = _uiState.value.freeCell ?: return
        if (state.isWon) return
        val pile = state.tableauPiles[fromColumn]
        if (pile.isEmpty()) return
        val card = pile.last()
        if (!canPlaceOnFoundation(card, state.foundations[foundationIndex], foundationIndex)) return
        saveHistory()
        val newPiles = state.tableauPiles.toMutableList()
        newPiles[fromColumn] = pile.dropLast(1)
        val newFoundations = state.foundations.toMutableList()
        newFoundations[foundationIndex] = newFoundations[foundationIndex] + card
        _uiState.update {
            it.copy(freeCell = state.copy(
                tableauPiles = newPiles,
                foundations = newFoundations,
                moveCount = state.moveCount + 1
            ))
        }
        checkFreeCellWin()
    }

    fun freeCellMoveTableauToTableau(fromColumn: Int, cardIndex: Int, toColumn: Int) {
        val state = _uiState.value.freeCell ?: return
        if (state.isWon) return
        val fromPile = state.tableauPiles[fromColumn]
        if (cardIndex < 0 || cardIndex >= fromPile.size) return
        val movingCards = fromPile.subList(cardIndex, fromPile.size)
        if (!isFreeCellSequence(movingCards)) return
        val toPile = state.tableauPiles[toColumn]
        if (movingCards.size > 1) {
            val emptyFreeCells = state.freeCells.count { it == null }
            val emptyColumns = state.tableauPiles.indices.count { it != fromColumn && it != toColumn && state.tableauPiles[it].isEmpty() }
            val maxMove = (1 + emptyFreeCells) * (1 shl emptyColumns)
            if (movingCards.size > maxMove) return
        }
        if (!canPlaceOnFreeCellTableau(movingCards.first(), toPile)) return
        saveHistory()
        val newPiles = state.tableauPiles.toMutableList()
        newPiles[fromColumn] = fromPile.subList(0, cardIndex)
        newPiles[toColumn] = toPile + movingCards
        _uiState.update {
            it.copy(freeCell = state.copy(
                tableauPiles = newPiles,
                moveCount = state.moveCount + 1
            ))
        }
    }

    private fun isFreeCellSequence(cards: List<Card>): Boolean =
        cards.zipWithNext().all { (a, b) -> a.alternatesColorWith(b) && a.isOneHigherThan(b) }

    private fun canPlaceOnFreeCellTableau(card: Card, pile: List<Card>): Boolean =
        pile.isEmpty() || (card.alternatesColorWith(pile.last()) && pile.last().isOneHigherThan(card))

    private fun checkFreeCellWin() {
        val state = _uiState.value.freeCell ?: return
        if (state.foundations.all { it.size == 13 }) {
            _uiState.update { it.copy(freeCell = state.copy(isWon = true)) }
            onGameWon(GameMode.FREECELL, state.elapsedSeconds, state.moveCount, state.usedUndo)
        }
    }

    // --- Pyramid ---

    fun newPyramidGame(config: GameConfig) {
        val deck = createShuffledDeck()
        var index = 0
        val rows = mutableListOf<List<Card?>>()
        for (r in 0 until 7) {
            val row = mutableListOf<Card?>()
            for (c in 0..r) {
                row.add(deck[index]); index++
            }
            rows.add(row)
        }
        val stock = deck.subList(index, deck.size).toList()
        val variant = if (config.relaxed) "RELAXED" else "ORIGINAL"
        _uiState.value = SolitaireUiState(
            gameMode = GameMode.PYRAMID,
            pyramid = PyramidState(
                pyramid = rows,
                stock = stock,
                waste = emptyList(),
                relaxed = config.relaxed,
                variant = variant
            ),
            history = emptyList()
        )
        statsRepository.recordGamePlayed(GameMode.PYRAMID, variant)
    }

    /** A pyramid card is exposed when both cards resting on it have been removed. */
    private fun isPyramidCardExposed(state: PyramidState, row: Int, col: Int): Boolean {
        if (state.pyramid[row][col] == null) return false
        if (row == state.pyramid.lastIndex) return true
        val below = state.pyramid[row + 1]
        return below[col] == null && below[col + 1] == null
    }

    /** Whether the card identified by [id] is exposed (directly selectable). */
    private fun isPyramidPlayable(state: PyramidState, id: String): Boolean = when {
        id == "waste" -> state.waste.isNotEmpty()
        id.startsWith("pyr_") -> {
            val (r, c) = parsePyramidId(id)
            isPyramidCardExposed(state, r, c)
        }
        else -> false
    }

    /**
     * Whether the card [coverId] is the *only* remaining card covering
     * [coveredId] (i.e. removing [coverId] would expose it). This enables the
     * standard Pyramid variation: an exposed card may be taken together with a
     * card it partially covers, when it is that card's last cover.
     */
    private fun isPyramidSoleCover(state: PyramidState, coverId: String, coveredId: String): Boolean {
        if (!coverId.startsWith("pyr_") || !coveredId.startsWith("pyr_")) return false
        val (cr, cc) = parsePyramidId(coverId)
        val (pr, pc) = parsePyramidId(coveredId)
        if (cr != pr + 1) return false
        // A card at (pr,pc) is covered by (pr+1, pc) and (pr+1, pc+1).
        val below = state.pyramid[pr + 1]
        return when (cc) {
            pc -> below.getOrNull(pc + 1) == null       // cover is the left child; right one gone
            pc + 1 -> below.getOrNull(pc) == null       // cover is the right child; left one gone
            else -> false
        }
    }

    /** Whether the two cards may be removed together (ranks sum to 13). */
    private fun canPyramidRemovePair(state: PyramidState, idA: String, idB: String): Boolean {
        if (idA == idB) return false
        val a = pyramidCardAt(state, idA) ?: return false
        val b = pyramidCardAt(state, idB) ?: return false
        if (a.rank.value + b.rank.value != 13) return false
        val aExposed = isPyramidPlayable(state, idA)
        val bExposed = isPyramidPlayable(state, idB)
        if (aExposed && bExposed) return true
        // Partially-covered variation (always allowed): one card is exposed and
        // is the sole remaining cover of the other.
        if (aExposed && isPyramidSoleCover(state, idA, idB)) return true
        if (bExposed && isPyramidSoleCover(state, idB, idA)) return true
        return false
    }

    private fun parsePyramidId(id: String): Pair<Int, Int> {
        val parts = id.removePrefix("pyr_").split("_")
        return parts[0].toInt() to parts[1].toInt()
    }

    private fun pyramidCardAt(state: PyramidState, id: String): Card? = when {
        id == "waste" -> state.waste.lastOrNull()
        id.startsWith("pyr_") -> {
            val (r, c) = parsePyramidId(id)
            state.pyramid.getOrNull(r)?.getOrNull(c)
        }
        else -> null
    }

    /** Remove the card identified by [id] from the pyramid or the waste. */
    private fun pyramidRemove(state: PyramidState, id: String): PyramidState = when {
        id == "waste" -> state.copy(waste = state.waste.dropLast(1))
        id.startsWith("pyr_") -> {
            val (r, c) = parsePyramidId(id)
            val newPyramid = state.pyramid.map { it.toMutableList() }
            newPyramid[r][c] = null
            state.copy(pyramid = newPyramid.map { it.toList() })
        }
        else -> state
    }

    /**
     * Tap a card in the pyramid or on the waste. If it forms a valid pair with
     * the current selection (both exposed, or an exposed card plus a card it is
     * the sole cover of), both are removed. Kings (value 13) are removed on their
     * own. Otherwise an exposed tap becomes the new selection.
     */
    override fun pyramidTapCard(id: String) {
        val state = _uiState.value.pyramid ?: return
        if (state.isWon) return
        pyramidCardAt(state, id) ?: return

        // If a selection exists and this tap completes a pair, remove both.
        val selected = state.selectedId
        if (selected != null && canPyramidRemovePair(state, selected, id)) {
            saveHistory()
            val afterFirst = pyramidRemove(state, id)
            val afterSecond = pyramidRemove(afterFirst, selected).copy(
                selectedId = null,
                moveCount = state.moveCount + 1
            )
            _uiState.update { it.copy(pyramid = afterSecond) }
            checkPyramidWin()
            return
        }

        // Otherwise the tapped card must be exposed to select it or remove a King.
        if (!isPyramidPlayable(state, id)) return
        val card = pyramidCardAt(state, id) ?: return

        if (card.rank.value == 13) {
            saveHistory()
            val removed = pyramidRemove(state, id).copy(
                selectedId = null,
                moveCount = state.moveCount + 1
            )
            _uiState.update { it.copy(pyramid = removed) }
            checkPyramidWin()
            return
        }

        _uiState.update {
            it.copy(pyramid = state.copy(selectedId = if (selected == id) null else id))
        }
    }

    override fun pyramidDealStock() {
        val state = _uiState.value.pyramid ?: return
        if (state.isWon) return
        if (state.stock.isEmpty()) {
            // Original mode is a single pass (no recycle); relaxed is unlimited.
            if (!state.relaxed || state.waste.isEmpty()) return
            saveHistory()
            _uiState.update {
                it.copy(pyramid = state.copy(
                    stock = state.waste.reversed(),
                    waste = emptyList(),
                    selectedId = null,
                    moveCount = state.moveCount + 1
                ))
            }
            return
        }
        saveHistory()
        _uiState.update {
            it.copy(pyramid = state.copy(
                stock = state.stock.dropLast(1),
                waste = state.waste + state.stock.last(),
                moveCount = state.moveCount + 1
            ))
        }
    }

    private fun checkPyramidWin() {
        val state = _uiState.value.pyramid ?: return
        if (state.pyramid.all { row -> row.all { it == null } }) {
            _uiState.update { it.copy(pyramid = state.copy(isWon = true)) }
            onGameWon(GameMode.PYRAMID, state.elapsedSeconds, state.moveCount, state.usedUndo)
        }
    }

    // --- Shared ---

    /**
     * The cards a drag from [sourceId] carries, read from the live state rather than
     * from whatever the UI captured when the gesture was set up. Empty when the source
     * no longer holds a card (a stale slot), which is the signal not to start a drag.
     */
    private fun draggedCards(sourceId: String): List<Card> = with(_uiState.value) {
        when {
            sourceId == "waste" -> klondike?.waste?.lastOrNull()?.let { listOf(it) } ?: emptyList()
            sourceId.startsWith("freecell_") -> {
                val ci = sourceId.removePrefix("freecell_").toIntOrNull() ?: return emptyList()
                freeCell?.freeCells?.getOrNull(ci)?.let { listOf(it) } ?: emptyList()
            }
            sourceId.startsWith("tableau_") -> {
                val parts = sourceId.removePrefix("tableau_").split("_")
                val col = parts.getOrNull(0)?.toIntOrNull() ?: return emptyList()
                val idx = parts.getOrNull(1)?.toIntOrNull() ?: return emptyList()
                val faceUp = when (gameMode) {
                    GameMode.KLONDIKE -> klondike?.tableauPiles?.getOrNull(col)?.faceUp
                    GameMode.SPIDER -> spider?.tableauPiles?.getOrNull(col)?.faceUp
                    GameMode.FREECELL -> freeCell?.tableauPiles?.getOrNull(col)
                    else -> null
                } ?: return emptyList()
                if (idx in faceUp.indices) faceUp.subList(idx, faceUp.size) else emptyList()
            }
            else -> emptyList()
        }
    }

    /** A foundation only ever accepts a drag that carries the single top card. */
    private fun isSingleCardDrag(sourceId: String): Boolean = draggedCards(sourceId).size == 1

    fun tryMoveByDrag(sourceId: String, dropOffset: Offset, cardSize: androidx.compose.ui.unit.IntSize = androidx.compose.ui.unit.IntSize.Zero) {
        val targetId = resolveDropTarget(dropOffset, cardSize) ?: return

        when (_uiState.value.gameMode) {
            GameMode.KLONDIKE -> handleKlondikeDrop(sourceId, targetId)
            GameMode.SPIDER -> handleSpiderDrop(sourceId, targetId)
            GameMode.FREECELL -> handleFreeCellDrop(sourceId, targetId)
            GameMode.PYRAMID -> {} // Pyramid is tap-based, not drag-based.
            null -> {}
        }
    }

    /**
     * Resolves the drop target for the leading (top) card of the dragged stack.
     *
     * The previous implementation picked the target whose bounds contained the
     * single pointer-derived point (`dropOffset`). For a large tableau stack
     * the user drops by the bottom and the center point of the dragged card
     * can fall outside a short destination column (or land on an adjacent
     * pile), causing valid moves to be silently discarded — the symptom of
     * issue #505.
     *
     * The fix uses the bounds of the leading card being dropped (top of the
     * moving run, centered on the finger) and picks the target with the
     * greatest overlap area. A non-overlapping point-in-rect check is kept as
     * a fallback for very small drags or zero-size rects.
     */
    private fun resolveDropTarget(dropOffset: Offset, cardSize: androidx.compose.ui.unit.IntSize): String? {
        // Prefer overlap-area of the leading card's bounds.
        if (cardSize.width > 0 && cardSize.height > 0) {
            val w = cardSize.width.toFloat()
            val h = cardSize.height.toFloat()
            val cardRect = Rect(dropOffset.x - w / 2f, dropOffset.y - h / 2f, dropOffset.x + w / 2f, dropOffset.y + h / 2f)
            val overlapped = dropTargets.maxByOrNull { (_, rect) -> overlapArea(cardRect, rect) }
            if (overlapped != null && overlapArea(cardRect, overlapped.value) > 0f) {
                return overlapped.key
            }
        }
        // Fallback: any rect containing the center point.
        return dropTargets.entries.find { (_, rect) -> rect.contains(dropOffset) }?.key
    }

    private fun handleKlondikeDrop(sourceId: String, targetId: String) {
        when {
            targetId.startsWith("foundation_") -> {
                val fi = targetId.removePrefix("foundation_").toInt()
                if (isSingleCardDrag(sourceId)) {
                    when {
                        sourceId == "waste" -> klondikeMoveWasteToFoundation(fi)
                        sourceId.startsWith("tableau_") -> {
                            val col = sourceId.removePrefix("tableau_").substringBefore("_").toInt()
                            klondikeMoveTableauToFoundation(col, fi)
                        }
                    }
                }
            }
            targetId.startsWith("tableau_") -> {
                val toCol = targetId.removePrefix("tableau_").toInt()
                when {
                    sourceId == "waste" -> klondikeMoveWasteToTableau(toCol)
                    sourceId.startsWith("tableau_") -> {
                        val parts = sourceId.removePrefix("tableau_").split("_")
                        val fromCol = parts[0].toInt()
                        val cardIdx = parts.getOrNull(1)?.toInt() ?: return
                        klondikeMoveTableauToTableau(fromCol, cardIdx, toCol)
                    }
                }
            }
        }
    }

    private fun handleSpiderDrop(sourceId: String, targetId: String) {
        if (!targetId.startsWith("tableau_") || !sourceId.startsWith("tableau_")) return
        val toCol = targetId.removePrefix("tableau_").toInt()
        val parts = sourceId.removePrefix("tableau_").split("_")
        val fromCol = parts[0].toInt()
        val cardIdx = parts.getOrNull(1)?.toInt() ?: return
        spiderMoveCards(fromCol, cardIdx, toCol)
    }

    private fun handleFreeCellDrop(sourceId: String, targetId: String) {
        when {
            targetId.startsWith("freecell_") -> {
                val ci = targetId.removePrefix("freecell_").toInt()
                if (sourceId.startsWith("tableau_")) {
                    val col = sourceId.removePrefix("tableau_").substringBefore("_").toInt()
                    freeCellMoveToFreeCell(col, ci)
                }
            }
            targetId.startsWith("foundation_") -> {
                val fi = targetId.removePrefix("foundation_").toInt()
                if (isSingleCardDrag(sourceId)) {
                    when {
                        sourceId.startsWith("freecell_") -> {
                            val ci = sourceId.removePrefix("freecell_").toInt()
                            freeCellMoveFreeCellToFoundation(ci, fi)
                        }
                        sourceId.startsWith("tableau_") -> {
                            val col = sourceId.removePrefix("tableau_").substringBefore("_").toInt()
                            freeCellMoveTableauToFoundation(col, fi)
                        }
                    }
                }
            }
            targetId.startsWith("tableau_") -> {
                val toCol = targetId.removePrefix("tableau_").toInt()
                when {
                    sourceId.startsWith("freecell_") -> {
                        val ci = sourceId.removePrefix("freecell_").toInt()
                        freeCellMoveFromFreeCell(ci, toCol)
                    }
                    sourceId.startsWith("tableau_") -> {
                        val parts = sourceId.removePrefix("tableau_").split("_")
                        val fromCol = parts[0].toInt()
                        val cardIdx = parts.getOrNull(1)?.toInt() ?: return
                        freeCellMoveTableauToTableau(fromCol, cardIdx, toCol)
                    }
                }
            }
        }
    }

    // --- Tap to move (auto) ---

    override fun autoMove(sourceId: String) {
        when (_uiState.value.gameMode) {
            GameMode.KLONDIKE -> klondikeAutoMove(sourceId)
            GameMode.FREECELL -> freeCellAutoMove(sourceId)
            else -> {} // Spider has no foundations; Pyramid is already tap-based.
        }
    }

    private fun klondikeAutoMove(sourceId: String) {
        val state = _uiState.value.klondike ?: return
        if (state.isWon) return
        val cards = draggedCards(sourceId)
        if (cards.isEmpty()) return
        val topCard = cards.first()

        val fromCol = if (sourceId.startsWith("tableau_"))
            sourceId.removePrefix("tableau_").substringBefore("_").toIntOrNull() else null
        val fromIdx = if (sourceId.startsWith("tableau_"))
            sourceId.removePrefix("tableau_").split("_").getOrNull(1)?.toIntOrNull() ?: 0 else 0

        // 1) Foundation — only a single top card can go up, and it is the preferred move.
        if (cards.size == 1) {
            for (fi in state.foundations.indices) {
                if (canPlaceOnFoundation(topCard, state.foundations[fi], fi)) {
                    when {
                        sourceId == "waste" -> klondikeMoveWasteToFoundation(fi)
                        fromCol != null -> klondikeMoveTableauToFoundation(fromCol, fi)
                        else -> return
                    }
                    return
                }
            }
        }

        // 2) Tableau — first column that legally accepts the run.
        for (toCol in state.tableauPiles.indices) {
            if (toCol == fromCol) continue
            val toPile = state.tableauPiles[toCol]
            if (!canPlaceOnKlondikeTableau(topCard, toPile)) continue
            // Skip shuffling a lone King between empty columns — it accomplishes nothing.
            val toEmpty = toPile.faceUp.isEmpty() && toPile.faceDown.isEmpty()
            if (toEmpty && fromCol != null && fromIdx == 0 && state.tableauPiles[fromCol].faceDown.isEmpty()) continue
            when {
                sourceId == "waste" -> klondikeMoveWasteToTableau(toCol)
                fromCol != null -> klondikeMoveTableauToTableau(fromCol, fromIdx, toCol)
                else -> return
            }
            return
        }
    }

    private fun freeCellAutoMove(sourceId: String) {
        val state = _uiState.value.freeCell ?: return
        if (state.isWon) return
        val cards = draggedCards(sourceId)
        if (cards.isEmpty()) return
        val topCard = cards.first()
        val single = cards.size == 1

        val fromCol = if (sourceId.startsWith("tableau_"))
            sourceId.removePrefix("tableau_").substringBefore("_").toIntOrNull() else null
        val fromIdx = if (sourceId.startsWith("tableau_"))
            sourceId.removePrefix("tableau_").split("_").getOrNull(1)?.toIntOrNull() ?: 0 else 0
        val fromCell = if (sourceId.startsWith("freecell_"))
            sourceId.removePrefix("freecell_").toIntOrNull() else null

        // 1) Foundation (single card only), the preferred move.
        if (single) {
            for (fi in state.foundations.indices) {
                if (canPlaceOnFoundation(topCard, state.foundations[fi], fi)) {
                    when {
                        fromCell != null -> freeCellMoveFreeCellToFoundation(fromCell, fi)
                        fromCol != null -> freeCellMoveTableauToFoundation(fromCol, fi)
                        else -> return
                    }
                    return
                }
            }
        }

        // 2) Tableau — first column that legally accepts the run.
        for (toCol in state.tableauPiles.indices) {
            if (toCol == fromCol) continue
            val toPile = state.tableauPiles[toCol]
            if (!canPlaceOnFreeCellTableau(topCard, toPile)) continue
            // Skip moving a lone card between empty columns — pointless churn.
            if (toPile.isEmpty() && fromCol != null && single && state.tableauPiles[fromCol].size == 1) continue
            when {
                fromCell != null -> freeCellMoveFromFreeCell(fromCell, toCol)
                fromCol != null -> freeCellMoveTableauToTableau(fromCol, fromIdx, toCol)
                else -> return
            }
            return
        }

        // 3) Empty free cell — last resort for a single card off a tableau column.
        if (single && fromCol != null) {
            val emptyCell = state.freeCells.indexOfFirst { it == null }
            if (emptyCell >= 0) freeCellMoveToFreeCell(fromCol, emptyCell)
        }
    }

    /**
     * Starts a drag from [sourceId], deriving the carried cards from current state.
     * Returns false (and starts nothing) when that source holds no card.
     */
    override fun startDrag(sourceId: String, startPos: Offset, cardSize: androidx.compose.ui.unit.IntSize): Boolean {
        val cards = draggedCards(sourceId)
        if (cards.isEmpty()) {
            _dragInfo.value = null
            return false
        }
        _dragInfo.value = DragInfo(cards, sourceId, startPos, startPos, cardSize)
        return true
    }

    override fun updateDrag(offset: Offset) {
        _dragInfo.update { it?.copy(offset = offset) }
    }

    override fun endDrag(dropOffset: Offset, cardSize: androidx.compose.ui.unit.IntSize) {
        val info = _dragInfo.value ?: return
        // Use the cardSize recorded at drag start (finger owns that card), with the
        // current drop size as fallback so call sites lacking a size still resolve.
        val effectiveSize = if (cardSize.width > 0 && cardSize.height > 0) cardSize else info.cardSize
        tryMoveByDrag(info.sourceId, dropOffset, effectiveSize)
        _dragInfo.value = null
    }

    override fun cancelDrag() {
        _dragInfo.value = null
    }

    override fun undo() {
        val history = _uiState.value.history
        if (history.isEmpty()) return
        val prev = history.last()
        val newHistory = history.dropLast(1)
        when (prev) {
            is KlondikeState -> _uiState.update {
                it.copy(klondike = prev.copy(usedUndo = true), history = newHistory)
            }
            is SpiderState -> _uiState.update {
                it.copy(spider = prev.copy(usedUndo = true), history = newHistory)
            }
            is FreeCellState -> _uiState.update {
                it.copy(freeCell = prev.copy(usedUndo = true), history = newHistory)
            }
            is PyramidState -> _uiState.update {
                it.copy(pyramid = prev.copy(usedUndo = true), history = newHistory)
            }
        }
    }

    override fun restart() {
        val mode = _uiState.value.gameMode ?: return
        selectMode(mode, currentConfig())
    }

    fun incrementTimer() {
        _uiState.update { state ->
            when (state.gameMode) {
                GameMode.KLONDIKE -> state.copy(
                    klondike = state.klondike?.let { if (!it.isWon) it.copy(elapsedSeconds = it.elapsedSeconds + 1) else it }
                )
                GameMode.SPIDER -> state.copy(
                    spider = state.spider?.let { if (!it.isWon) it.copy(elapsedSeconds = it.elapsedSeconds + 1) else it }
                )
                GameMode.FREECELL -> state.copy(
                    freeCell = state.freeCell?.let { if (!it.isWon) it.copy(elapsedSeconds = it.elapsedSeconds + 1) else it }
                )
                GameMode.PYRAMID -> state.copy(
                    pyramid = state.pyramid?.let { if (!it.isWon) it.copy(elapsedSeconds = it.elapsedSeconds + 1) else it }
                )
                null -> state
            }
        }
    }

    fun dismissAchievementNotification() {
        achievementsManager.dismissNotification()
    }

    private fun saveHistory() {
        val state = _uiState.value
        val current: Any = when (state.gameMode) {
            GameMode.KLONDIKE -> state.klondike ?: return
            GameMode.SPIDER -> state.spider ?: return
            GameMode.FREECELL -> state.freeCell ?: return
            GameMode.PYRAMID -> state.pyramid ?: return
            null -> return
        }
        _uiState.update { it.copy(history = it.history + current) }
    }

    private fun onGameWon(mode: GameMode, timeSeconds: Int, moves: Int, usedUndo: Boolean) {
        statsRepository.recordGameWon(mode, currentVariant(), timeSeconds, moves)
        achievementsManager.onAchievementUnlocked("first_win")
        when (mode) {
            GameMode.KLONDIKE -> {
                achievementsManager.onAchievementUnlocked("klondike_first")
                if (timeSeconds < 180) achievementsManager.onAchievementUnlocked("speed_demon")
            }
            GameMode.SPIDER -> achievementsManager.onAchievementUnlocked("spider_first")
            GameMode.FREECELL -> achievementsManager.onAchievementUnlocked("freecell_first")
            GameMode.PYRAMID -> achievementsManager.onAchievementUnlocked("pyramid_first")
        }
        if (!usedUndo) achievementsManager.onAchievementUnlocked("no_undo")
        val totalWins = statsRepository.getTotalGamesWon()
        achievementsManager.onProgressUpdated("wins_10", totalWins)
        achievementsManager.onProgressUpdated("wins_50", totalWins)
        achievementsManager.onProgressUpdated("win_streak_5", statsRepository.getBestWinStreak())
    }
}


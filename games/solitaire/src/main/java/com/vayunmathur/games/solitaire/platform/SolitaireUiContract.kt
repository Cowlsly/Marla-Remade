package com.vayunmathur.games.solitaire.platform

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The UI contract between [SolitaireViewModel] and the boards.
 *
 * The boards and the card widgets they share take this interface rather than the ViewModel
 * itself, so they can be rendered by a `@Preview` — which is what the store listing images
 * are generated from. It lives in `platform` rather than next to the boards so the dependency
 * runs one way: `ui` depends on `platform`, and the ViewModel implements this.
 *
 * Every member has a no-op default, so [Noop] is the whole implementation a preview needs.
 */
interface SolitaireActions {

    /** The drag in flight, or null. A preview has none, which is why the default is empty. */
    val dragInfo: StateFlow<DragInfo?> get() = NoDrag

    /** Drop zones, registered by the piles as they are laid out. */
    val dropTargets: MutableMap<String, Rect> get() = DiscardedDropTargets

    fun startDrag(sourceId: String, startPos: Offset, cardSize: IntSize = IntSize.Zero): Boolean = false
    fun updateDrag(offset: Offset) {}
    fun endDrag(dropOffset: Offset, cardSize: IntSize = IntSize.Zero) {}
    fun cancelDrag() {}

    fun drawFromStock() {}
    fun klondikeAutoComplete() {}
    fun dealSpiderStock() {}
    fun pyramidTapCard(id: String) {}
    fun pyramidDealStock() {}

    /**
     * Tap-to-move: send the card (or valid run) at [sourceId] to its best legal
     * destination — a foundation first, otherwise a valid tableau/free-cell spot.
     * A no-op when there is no legal move. Klondike and FreeCell only; Spider has no
     * foundations and Pyramid is already tap-based.
     */
    fun autoMove(sourceId: String) {}

    fun undo() {}
    fun restart() {}
    fun giveUp() {}

    companion object {
        val Noop: SolitaireActions = object : SolitaireActions {}
    }
}

private val NoDrag: StateFlow<DragInfo?> = MutableStateFlow(null)

/** Somewhere for a [SolitaireActions.Noop] board to register its drop zones and forget them. */
private val DiscardedDropTargets: MutableMap<String, Rect> = mutableMapOf()


package com.vayunmathur.launcher.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.platform.ComponentKey
import com.vayunmathur.launcher.platform.ShortcutEntry

/**
 * What is being dragged.
 *
 * [itemId] is null for something that is not on the workspace yet — an app picked up in the
 * drawer. Launcher3 draws the same distinction between a `PendingAddItemInfo` and an existing
 * `ItemInfo`, and it is what tells a drop handler to insert rather than move.
 */
data class DragPayload(
    val itemId: Long?,
    val type: LauncherItemType,
    val label: String,
    val key: ComponentKey? = null,
    /**
     * The shortcut being pinned, for something dragged out of an item's menu. Like [key] this is
     * only set when [itemId] is null: it is what the drop needs in order to create the row.
     */
    val shortcut: ShortcutEntry? = null,
    /**
     * Whether the drop bar may offer to uninstall this: false for a system app and for anything in
     * another profile.
     */
    val canUninstall: Boolean = false,
    /**
     * The hosted widget's id, for a widget being moved.
     *
     * Carried on the payload rather than looked up on landing, because a widget dropped onto a
     * *different* page cannot be found in the destination page's items - that list is still the
     * pre-drop one when the drop fires.
     */
    val appWidgetId: Int? = null,
    /** Origin cell and span. The position half is what tells a move from a release in place. */
    val rect: CellRect = CellRect(0, 0),
    val origin: ContainerRef = ContainerRef.Desktop,
    val originScreen: Int = 0,
    /**
     * Bounds of the thing being dragged, so the drag layer can draw it at the right scale — and
     * so a refused drop has somewhere to fly back to.
     */
    val sourceBounds: Rect = Rect.Zero,
)

/** A registered drop target. */
class DropTarget(
    val bounds: Rect,
    /**
     * Higher wins when targets overlap. The Remove bar, a folder icon and the hotseat all sit
     * over a page, and all three must beat the page itself.
     */
    val priority: Int,
    val accepts: (DragPayload) -> Boolean,
    /**
     * Commits the drop and returns **where the item ended up**, in window coordinates, so the
     * drag layer can fly there rather than blinking out under the finger.
     *
     * Returning null means the target would not take it, and the item animates back to
     * [DragPayload.sourceBounds] instead — which is also the right answer for a drop inside a
     * folder that is not on one of its children.
     */
    val onDrop: (DragPayload, Offset) -> Rect?,
    val onEnter: (DragPayload) -> Unit,
    val onExit: () -> Unit,
)

/**
 * Hoisted drag state.
 *
 * Not in the ViewModel, deliberately. A drag crosses page to page, home to hotseat, icon to
 * folder and icon to the Remove bar, and none of it may reach the database until the drop
 * commits: Room's invalidation tracker fires promptly here (journal mode is forced to
 * `TRUNCATE`), so a per-frame write would re-emit the whole workspace mid-drag and visibly
 * reload every hosted widget. So the in-flight position lives here, and exactly one write
 * happens at the end.
 *
 * A release does not end the drag. [drop] commits and then leaves the payload in place with
 * [landing] set, so the item can be seen travelling from the finger into the cell it was given;
 * [settled] is what finally clears it. The item stays dimmed at its destination for those few
 * frames, which is what stops two copies of it being on screen at once.
 */
class LauncherDragController {

    var payload by mutableStateOf<DragPayload?>(null)
        private set

    /** Finger position, in window coordinates. */
    var position by mutableStateOf(Offset.Zero)
        private set

    /**
     * Which target the finger is over, by its registration key.
     *
     * The **key**, not the target, is what is observable — and that is not a detail. [dropTarget]
     * re-registers on every composition because its handlers close over state a recomposition may
     * have replaced, and a page recomposes on every frame of a drag because it reads [position]. So
     * the object identity of the target under the finger changes sixty times a second while nothing
     * about the drag has changed at all. Anything keyed on the object therefore fires per frame:
     * that is what turned [DragFeedback]'s one tick per target into a continuous buzz.
     */
    var activeTargetKey by mutableStateOf<Any?>(null)
        private set

    /**
     * The target itself, deliberately not observable: it is replaced on every recomposition, so
     * reading it from a composable would recompose that composable every frame. Only the drop needs
     * it, and only at the moment of the drop.
     */
    private var activeTarget: DropTarget? = null

    /**
     * Where the released item is travelling to, in window coordinates. Non-null only between a
     * release and the end of the settle.
     */
    var landing by mutableStateOf<Rect?>(null)
        private set

    val isDragging: Boolean get() = payload != null

    /** True while the released item is still flying to where it landed. */
    val isSettling: Boolean get() = landing != null

    private val targets = mutableMapOf<Any, DropTarget>()
    private val sources = mutableMapOf<Any, Pair<Rect, () -> DragPayload>>()

    fun registerTarget(key: Any, target: DropTarget) {
        targets[key] = target
    }

    fun unregisterTarget(key: Any) {
        targets.remove(key)
    }

    fun registerSource(key: Any, bounds: Rect, payload: () -> DragPayload) {
        sources[key] = bounds to payload
    }

    fun unregisterSource(key: Any) {
        sources.remove(key)
    }

    /** The draggable thing under [point], if any. Used by the single root gesture owner. */
    fun sourceAt(point: Offset): DragPayload? =
        sources.values.lastOrNull { it.first.contains(point) }?.second?.invoke()

    fun start(payload: DragPayload, at: Offset) {
        this.payload = payload
        position = at
        landing = null
        updateTarget(at)
    }

    fun move(to: Offset) {
        if (payload == null || isSettling) return
        position = to
        updateTarget(to)
    }

    /**
     * Commits at the current position and begins the settle. Returns whether a target took it,
     * which is all the caller needs to know to pick a haptic.
     */
    fun drop(): Boolean {
        val current = payload ?: return false
        val landed = activeTarget?.onDrop?.invoke(current, position)
        activeTarget = null
        activeTargetKey = null
        // Refused: back where it came from, so it is seen returning rather than vanishing. An item
        // with no origin on screen — an app straight out of a drawer that has since closed — has
        // nowhere to return to, so it simply ends.
        landing = landed ?: current.sourceBounds.takeIf { !it.isEmpty }
        if (landing == null) clear()
        return landed != null
    }

    /** Ends the drag now that the settle animation has played out. */
    fun settled() = clear()

    fun cancel() {
        activeTarget?.onExit?.invoke()
        clear()
    }

    private fun clear() {
        payload = null
        activeTarget = null
        activeTargetKey = null
        landing = null
    }

    /**
     * Works out which target the finger is over, and reports a change only when it is a *different*
     * target rather than a fresh registration of the same one.
     */
    private fun updateTarget(at: Offset) {
        val current = payload ?: return
        val next = targets.entries
            .filter { it.value.bounds.contains(at) && it.value.accepts(current) }
            .maxByOrNull { it.value.priority }

        if (next?.key == activeTargetKey) {
            // The same target, re-registered by a recomposition: take its newer handlers, which may
            // close over a layout this one does not know about, and say nothing happened.
            activeTarget = next?.value
            return
        }
        activeTarget?.onExit?.invoke()
        activeTarget = next?.value
        activeTargetKey = next?.key
        next?.value?.onEnter?.invoke(current)
    }
}

val LocalLauncherDrag = staticCompositionLocalOf<LauncherDragController> {
    error("No LauncherDragController provided")
}

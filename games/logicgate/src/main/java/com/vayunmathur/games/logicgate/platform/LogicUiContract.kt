package com.vayunmathur.games.logicgate.platform

import androidx.compose.ui.geometry.Offset
import com.vayunmathur.games.logicgate.data.WireEnd

/**
 * Everything the circuit editor asks [LogicViewModel] to do.
 *
 * The editor takes this interface plus a [UiState] value rather than the ViewModel itself,
 * so it can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. Every method has a no-op default, so [Noop] is the whole implementation a
 * preview needs.
 *
 * The names match [LogicViewModel]'s existing methods exactly, so the ViewModel implements
 * this directly rather than through an adapter. Navigation is deliberately not here: it
 * belongs to the caller, not the ViewModel, and the screen takes it as plain lambdas.
 */
interface LogicActions {
    fun selectLevel(levelId: String) {}

    /** Returns the new gate's instance id; the editor ignores it, so the default is empty. */
    fun addGateAt(chipId: String, x: Float?, y: Float?): String = ""

    fun removeGate(instanceId: String) {}
    fun clearCircuit() {}
    fun selectGate(id: String?) {}
    fun undo() {}
    fun redo() {}

    /** In-drag position updates; the `…Finished` variants are what push undo history. */
    fun onGateMoved(instanceId: String, x: Float, y: Float) {}
    fun onGateMoveFinished(instanceId: String, x: Float, y: Float) {}
    fun onInputMoved(idx: Int, x: Float, y: Float) {}
    fun onInputMoveFinished(idx: Int, x: Float, y: Float) {}
    fun onOutputMoved(idx: Int, x: Float, y: Float) {}
    fun onOutputMoveFinished(idx: Int, x: Float, y: Float) {}

    fun startWiring(from: WireEnd) {}
    fun cancelWiring() {}
    fun updateGhostLine(end: Offset?) {}
    fun createWire(from: WireEnd, to: WireEnd) {}
    fun removeWire(wireId: String) {}
    fun removeOutputMapping(outIdx: Int) {}

    companion object {
        val Noop: LogicActions = object : LogicActions {}
    }
}

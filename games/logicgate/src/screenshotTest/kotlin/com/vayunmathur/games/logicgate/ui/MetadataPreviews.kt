package com.vayunmathur.games.logicgate.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.games.logicgate.data.Circuit
import com.vayunmathur.games.logicgate.data.IoPos
import com.vayunmathur.games.logicgate.data.OutputMapping
import com.vayunmathur.games.logicgate.data.PlacedChip
import com.vayunmathur.games.logicgate.data.Wire
import com.vayunmathur.games.logicgate.data.WireEnd
import com.vayunmathur.games.logicgate.platform.EvalStatus
import com.vayunmathur.games.logicgate.platform.LogicActions
import com.vayunmathur.games.logicgate.platform.UiState
import com.vayunmathur.games.logicgate.ui.GameScreen

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:games:logicgate`, rendered from Compose previews instead of
 * from an instrumented test on a device. See `common-conventions-preview-metadata`.
 *
 * Rendering goes through the app's real [LogicGateTheme] rather than `DynamicTheme` — this
 * app ships a fixed palette and never follows the wallpaper, so the theme it uses at
 * runtime is the one that belongs in the listing.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 */
class MetadataPreviews {

    /** Through XOR, so the map shows completed nodes, two playable ones and locked ones. */
    private val completed = setOf("NOT", "AND", "OR", "XOR")

    /**
     * A half-built XOR: NAND(a,b) and OR(a,b) both feed an AND, which is the classic
     * `(a|b) & !(a&b)` construction. Terminal positions are pinned so the layout does not
     * depend on the canvas measuring itself first.
     */
    private val gates = listOf(
        PlacedChip("g_nand", "NAND", x = 330f, y = 170f),
        PlacedChip("g_or", "OR", x = 330f, y = 470f),
        PlacedChip("g_and", "AND", x = 620f, y = 320f),
    )

    private val wires = listOf(
        Wire("w_a_nand", WireEnd("__IN_0", 0), WireEnd("g_nand", 0)),
        Wire("w_b_nand", WireEnd("__IN_1", 0), WireEnd("g_nand", 1)),
        Wire("w_a_or", WireEnd("__IN_0", 0), WireEnd("g_or", 0)),
        Wire("w_b_or", WireEnd("__IN_1", 0), WireEnd("g_or", 1)),
        Wire("w_nand_and", WireEnd("g_nand", 0), WireEnd("g_and", 0)),
        Wire("w_or_and", WireEnd("g_or", 0), WireEnd("g_and", 1)),
    )

    private val terminals = Circuit(
        inputPositions = mapOf(0 to IoPos(140f, 240f), 1 to IoPos(140f, 560f)),
        outputPositions = mapOf(0 to IoPos(900f, 400f)),
    )

    private fun circuit(outputFrom: String) = terminals.copy(
        gates = gates,
        wires = wires,
        outputMappings = listOf(OutputMapping(0, WireEnd(outputFrom, 0))),
    )

    /** NAND, NOT, AND, OR — everything the XOR level allows, so the palette is full. */
    private val unlocked = setOf("NAND", "NOT", "AND", "OR")

    @PreviewTest
    @Preview(name = "1-levels", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Levels() {
        LogicGateTheme {
            ProgressionScreen(completed = completed)
        }
    }

    @PreviewTest
    @Preview(name = "2-circuit", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Circuit() {
        LogicGateTheme {
            // Output still taken straight off the OR, so three of the four rows pass and
            // the testbench shows the one that does not.
            GameScreen(
                levelId = "XOR",
                state = UiState(
                    currentLevelId = "XOR",
                    circuit = circuit(outputFrom = "g_or"),
                    evalStatus = EvalStatus.Ok(passingRows = 3, totalRows = 4, isFullyCorrect = false, failingRows = listOf(3)),
                    canUndo = true,
                ),
                unlockedChips = unlocked,
                actions = LogicActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-solved", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Solved() {
        LogicGateTheme {
            // Same circuit with the output moved to the AND, which makes it a real XOR.
            GameScreen(
                levelId = "XOR",
                state = UiState(
                    currentLevelId = "XOR",
                    circuit = circuit(outputFrom = "g_and"),
                    evalStatus = EvalStatus.Ok(passingRows = 4, totalRows = 4, isFullyCorrect = true, failingRows = emptyList()),
                    canUndo = true,
                ),
                unlockedChips = unlocked,
                actions = LogicActions.Noop,
                initialShowWinDialog = true,
            )
        }
    }
}

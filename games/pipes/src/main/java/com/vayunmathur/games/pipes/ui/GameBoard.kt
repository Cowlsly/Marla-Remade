package com.vayunmathur.games.pipes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.platform.PipesGameState

@Composable
fun GameBoard(
    levelData: LevelData,
    gameState: PipesGameState,
    activeColor: Int?,
    activePath: List<CellPos>,
    onStartDraw: (CellPos) -> Unit,
    onExtendPath: (CellPos) -> Unit,
    onCommitDraw: () -> Unit,
    isLevelWon: Boolean,
    modifier: Modifier = Modifier,
    colorblind: Boolean = false
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
    val boardSize = minOf(maxWidth, maxHeight)
    val maxDim = maxOf(levelData.rows, levelData.cols)
    val cellSizeDp = boardSize / maxDim

    val cellSizePx = with(androidx.compose.ui.platform.LocalDensity.current) { cellSizeDp.toPx() }

    // Center the board within the square canvas using the actual cells' bounding
    // box, so non-square / non-rectangular shapes aren't stuck in the top-left.
    // Skip when explicit render positions are provided.
    val hasRenderPositions = levelData.renderPositions != null
    val (offsetCol, offsetRow) = if (hasRenderPositions || levelData.cells.isEmpty()) {
        0f to 0f
    } else {
        val minRow = levelData.cells.minOf { it.row }
        val maxRow = levelData.cells.maxOf { it.row }
        val minCol = levelData.cells.minOf { it.col }
        val maxCol = levelData.cells.maxOf { it.col }
        val usedCols = maxCol - minCol + 1
        val usedRows = maxRow - minRow + 1
        val ox = (maxDim - usedCols) / 2f - minCol
        val oy = (maxDim - usedRows) / 2f - minRow
        ox to oy
    }

    val cellRects = levelData.cells.associateWith { cell ->
        val (x, y) = levelData.renderPositions?.get(cell)?.let { it.x to it.y }
            ?: ((cell.col + offsetCol) to (cell.row + offsetRow))
        Rect(Offset(x * cellSizePx, y * cellSizePx), Size(cellSizePx, cellSizePx))
    }

    fun hitTest(offset: Offset): CellPos? {
        return cellRects.entries.firstOrNull { (_, rect) ->
            rect.contains(offset)
        }?.key
    }

    val boardBounds = if (cellRects.isEmpty()) Rect.Zero else Rect(
        left = cellRects.values.minOf { it.left },
        top = cellRects.values.minOf { it.top },
        right = cellRects.values.maxOf { it.right },
        bottom = cellRects.values.maxOf { it.bottom }
    )

    // While dragging, a finger that leaves the board keeps driving the border
    // cell nearest to it, so the user doesn't have to come back inside to
    // continue the pipe.
    fun hitTestDrag(offset: Offset): CellPos? {
        hitTest(offset)?.let { return it }
        if (cellRects.isEmpty() || boardBounds.contains(offset)) return null
        return cellRects.entries.minWithOrNull(
            compareBy(
                { squaredDistanceToRect(it.value, offset) },
                { (it.value.center - offset).getDistanceSquared() }
            )
        )?.key
    }

    Canvas(
        modifier = Modifier
            .size(boardSize)
            .pointerInput(levelData, isLevelWon) {
                if (isLevelWon) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pos = event.changes.firstOrNull()?.position ?: continue

                        when {
                            event.changes.any { it.pressed && !it.previousPressed } -> {
                                val cell = hitTest(pos)
                                if (cell != null) onStartDraw(cell)
                            }
                            event.changes.any { it.pressed } -> {
                                val cell = hitTestDrag(pos)
                                if (cell != null) onExtendPath(cell)
                            }
                            event.changes.any { !it.pressed && it.previousPressed } -> {
                                onCommitDraw()
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        for (cell in levelData.cells) {
            val rect = cellRects[cell] ?: continue
            drawEmptyCell(rect)
        }

        val displayPaths = if (activeColor != null) gameState.paths + (activeColor to activePath) else gameState.paths

        for ((colorIndex, path) in displayPaths) {
            if (path.isEmpty()) continue
            val color = PIPE_COLORS[colorIndex % PIPE_COLORS.size]

            for (i in path.indices) {
                val cell = path[i]
                val rect = cellRects[cell] ?: continue
                val connections = buildSet {
                    if (i > 0) directionBetween(cell, path[i - 1])?.let { add(it) }
                    if (i < path.lastIndex) directionBetween(cell, path[i + 1])?.let { add(it) }
                }
                drawPipeSegment(rect, connections, color)
            }
        }

        for (ep in levelData.endpoints) {
            val color = PIPE_COLORS[ep.colorIndex % PIPE_COLORS.size]
            for (cell in ep.cells) {
                val rect = cellRects[cell] ?: continue
                drawEndpointBall(rect, color)
                if (colorblind) drawColorLabel(rect, ep.colorIndex)
            }
        }
    }
    }
}

private fun squaredDistanceToRect(rect: Rect, point: Offset): Float {
    val dx = maxOf(rect.left - point.x, 0f, point.x - rect.right)
    val dy = maxOf(rect.top - point.y, 0f, point.y - rect.bottom)
    return dx * dx + dy * dy
}

private fun directionBetween(from: CellPos, to: CellPos): Direction? {
    return when {
        to.row < from.row -> Direction.UP
        to.row > from.row -> Direction.DOWN
        to.col < from.col -> Direction.LEFT
        to.col > from.col -> Direction.RIGHT
        else -> null
    }
}

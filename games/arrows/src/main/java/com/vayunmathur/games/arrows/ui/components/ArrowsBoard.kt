package com.vayunmathur.games.arrows.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.vayunmathur.games.arrows.R
import com.vayunmathur.games.arrows.data.ArrowPiece
import com.vayunmathur.games.arrows.data.ArrowsGameState
import com.vayunmathur.games.arrows.data.Direction
import com.vayunmathur.games.arrows.data.Mirror
import com.vayunmathur.games.arrows.domain.ArrowsRules
import com.vayunmathur.library.ui.MaterialTheme

/**
 * The board.
 *
 * Painted in one [drawBehind] pass rather than as a composable per cell: an arrow spans several cells
 * and has to be a single continuous stroke with one arrowhead, which per-cell composables cannot
 * express. Taps anywhere along an arrow are resolved by arithmetic on the touch position, which is
 * both simpler and more accurate than hit-testing a stack of overlapping boxes.
 *
 * Because a canvas carries no semantics, one transparent box per arrow is laid over its head cell to
 * give screen readers something to find and activate.
 */
@Composable
fun ArrowsBoard(
    game: ArrowsGameState,
    showRoutes: Boolean,
    onTapArrow: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val arrowColor = scheme.onSurface
    val blockedColor = scheme.error
    val mirrorColor = scheme.tertiary
    val dotColor = scheme.outlineVariant
    val cols = game.puzzle.cols

    // The route the last blocked arrow tried to take, up to and including what stopped it. Computed
    // here rather than stored, because it is derivable from the board and would only go stale.
    val blockedRoute = if (!showRoutes || game.blockedId < 0) emptyList() else {
        game.puzzle.pieces.firstOrNull { it.id == game.blockedId }?.let { piece ->
            val occupied = game.occupancy
            ArrowsRules.exitPath(game.puzzle, piece.head, piece.direction)
                .orEmpty()
                .let { route ->
                    val stop = route.indexOfFirst { it in occupied }
                    if (stop < 0) route else route.take(stop + 1)
                }
        }.orEmpty()
    }

    BoxWithConstraints(modifier) {
        val cell = maxWidth / cols

        Box(
            Modifier
                .size(cell * cols, cell * game.puzzle.rows)
                .pointerInput(game.puzzle, game.removed, game.isOver) {
                    detectTapGestures { offset ->
                        val cellPx = size.width.toFloat() / cols
                        val col = (offset.x / cellPx).toInt()
                        val row = (offset.y / cellPx).toInt()
                        if (!game.puzzle.contains(row, col)) return@detectTapGestures
                        game.pieceAt(row * cols + col)?.let { onTapArrow(it.id) }
                    }
                }
                .drawBehind {
                    val cellPx = size.width / cols
                    drawDots(game, cellPx, dotColor)
                    for ((index, mirror) in game.puzzle.mirrors) {
                        drawMirror(index, mirror, cols, cellPx, mirrorColor)
                    }
                    if (blockedRoute.isNotEmpty()) {
                        drawRoute(blockedRoute, cols, cellPx, blockedColor)
                    }
                    for (piece in game.remaining) {
                        val color = if (piece.id == game.blockedId) blockedColor else arrowColor
                        drawArrow(piece, cols, cellPx, color)
                    }
                }
        ) {
            // Semantics only: invisible, one per arrow, sitting on its head cell.
            for (piece in game.remaining) {
                val description = stringResource(
                    R.string.cd_arrow,
                    stringResource(piece.direction.spokenNameRes),
                    piece.head / cols + 1,
                    piece.head % cols + 1,
                )
                Box(
                    Modifier
                        .offset(x = cell * (piece.head % cols), y = cell * (piece.head / cols))
                        .size(cell)
                        .clickable { onTapArrow(piece.id) }
                        .clearAndSetSemantics { contentDescription = description }
                )
            }
            // Redirectors are not interactive, but a screen reader still has to know they are there.
            val redirector = stringResource(R.string.cd_redirector)
            for (index in game.puzzle.mirrors.keys) {
                Box(
                    Modifier
                        .offset(x = cell * (index % cols), y = cell * (index / cols))
                        .size(cell)
                        .clearAndSetSemantics { contentDescription = redirector }
                )
            }
        }
    }
}

/**
 * Faint dots at cell centres.
 *
 * A full grid of lines would compete with the arrows, which are themselves thin strokes. Dots give
 * enough of a lattice to judge alignment without adding a second set of lines to read past. Skipped
 * wherever something is drawn, so a dot never shows through a stroke.
 */
private fun DrawScope.drawDots(game: ArrowsGameState, cell: Float, color: Color) {
    val radius = cell * 0.035f
    val occupied = game.occupancy
    for (row in 0 until game.puzzle.rows) {
        for (col in 0 until game.puzzle.cols) {
            val index = row * game.puzzle.cols + col
            if (index in occupied || index in game.puzzle.mirrors) continue
            drawCircle(
                color = color,
                radius = radius,
                center = Offset((col + 0.5f) * cell, (row + 0.5f) * cell),
            )
        }
    }
}

/** The tile's diagonal, drawn as the mirror it is named for. */
private fun DrawScope.drawMirror(cell: Int, mirror: Mirror, cols: Int, size: Float, color: Color) {
    val row = cell / cols
    val col = cell % cols
    val inset = size * 0.22f
    val left = col * size + inset
    val right = (col + 1) * size - inset
    val top = row * size + inset
    val bottom = (row + 1) * size - inset
    val (start, end) = when (mirror) {
        Mirror.FORWARD -> Offset(left, bottom) to Offset(right, top)
        Mirror.BACK -> Offset(left, top) to Offset(right, bottom)
    }
    drawLine(color, start, end, strokeWidth = size * 0.09f, cap = StrokeCap.Round)
}

/**
 * One arrow: its body as a single joined stroke, and an arrowhead at the head.
 *
 * The stroke runs through cell centres, so a turn in the polyline becomes a rounded corner. The tip
 * reaches past the head's centre by [HEAD_REACH] to leave room for the barbs while staying inside its
 * own cell rather than poking into the next one.
 */
private fun DrawScope.drawArrow(piece: ArrowPiece, cols: Int, cell: Float, color: Color) {
    val stroke = cell * 0.11f
    fun centre(index: Int) = Offset((index % cols + 0.5f) * cell, (index / cols + 0.5f) * cell)

    val head = centre(piece.head)
    val tip = Offset(
        head.x + piece.direction.dCol * cell * HEAD_REACH,
        head.y + piece.direction.dRow * cell * HEAD_REACH,
    )

    val path = Path().apply {
        val first = centre(piece.cells.first())
        moveTo(first.x, first.y)
        for (index in piece.cells.drop(1)) {
            val point = centre(index)
            lineTo(point.x, point.y)
        }
        lineTo(tip.x, tip.y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    // Two barbs angled back from the tip. For an axis-aligned heading the perpendicular is just the
    // row and column components swapped, so no trigonometry is needed.
    val barb = cell * 0.26f
    val backRow = -piece.direction.dRow
    val backCol = -piece.direction.dCol
    val perpRow = piece.direction.dCol
    val perpCol = piece.direction.dRow
    for (side in intArrayOf(1, -1)) {
        drawLine(
            color = color,
            start = tip,
            end = Offset(
                tip.x + (backCol + perpCol * side) * barb,
                tip.y + (backRow + perpRow * side) * barb,
            ),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** How far past the head cell's centre the tip reaches, as a fraction of a cell. */
private const val HEAD_REACH = 0.34f

/**
 * The route a blocked arrow tried to take, drawn as a trail of dots behind the arrows.
 *
 * Dots rather than a line, so it reads as an attempt rather than another arrow, and stops on the cell
 * that turned it back — which is the cell the player needs to look at.
 */
private fun DrawScope.drawRoute(route: List<Int>, cols: Int, cell: Float, color: Color) {
    val faded = color.copy(alpha = 0.55f)
    for (index in route) {
        drawCircle(
            color = faded,
            radius = cell * 0.09f,
            center = Offset((index % cols + 0.5f) * cell, (index / cols + 0.5f) * cell),
        )
    }
}

/** Spoken name for a direction, for the board's accessibility description. */
val Direction.spokenNameRes: Int
    get() = when (this) {
        Direction.UP -> R.string.cd_up
        Direction.DOWN -> R.string.cd_down
        Direction.LEFT -> R.string.cd_left
        Direction.RIGHT -> R.string.cd_right
    }

package com.vayunmathur.games.arrows.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.vayunmathur.games.arrows.platform.ArrowMove
import com.vayunmathur.library.ui.MaterialTheme
import kotlinx.coroutines.delay

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
    move: ArrowMove?,
    onTapArrow: (Int) -> Unit,
    onMoveFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val arrowColor = scheme.onSurface
    val blockedColor = scheme.error
    val mirrorColor = scheme.tertiary
    val dotColor = scheme.outlineVariant
    val cols = game.puzzle.cols

    // How far along its route the moving arrow currently is, in cells. Driven by one animation per tap;
    // `0` whenever nothing is moving, so a settled board draws from `game` alone.
    val advance = remember(move) { Animatable(0f) }
    // Turns red only once it has actually run into something, not from the first frame.
    var showBlocked by remember(move) { mutableStateOf(false) }

    LaunchedEffect(move) {
        if (move == null) return@LaunchedEffect
        if (move.clears) {
            // Past the end of the route, so the tail clears the edge too rather than winking out on it.
            val target = (move.advance + move.route.size).toFloat()
            advance.animateTo(target, tween(cellMillis(move.advance + move.route.size), easing = LinearEasing))
        } else {
            // Wedged arrows still get a nudge, or a tap on one reads as the game ignoring the input.
            val target = if (move.advance == 0) NudgeCells else move.advance.toFloat()
            advance.animateTo(target, tween(cellMillis(move.advance), easing = LinearOutSlowInEasing))
            showBlocked = true
            delay(BlockedHoldMillis)
            advance.animateTo(0f, tween(cellMillis(move.advance), easing = FastOutSlowInEasing))
        }
        onMoveFinished()
    }

    // The route the last blocked arrow tried to take, up to and including what stopped it. Derived rather
    // than stored, so it cannot go stale against the board.
    val blockedRoute = if (!showRoutes || game.blockedId < 0) emptyList() else {
        game.puzzle.pieces.firstOrNull { it.id == game.blockedId }?.let { piece ->
            val travel = ArrowsRules.travel(game, piece)
            // route is body + path, so drop the body and keep one past where it stopped: the cell that
            // stopped it is the one the player needs to look at.
            travel.route.drop(piece.length).take(travel.advance + 1)
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
                        val moving = piece.id == move?.pieceId
                        val color = when {
                            moving && showBlocked -> blockedColor
                            piece.id == game.blockedId -> blockedColor
                            else -> arrowColor
                        }
                        if (moving && move != null) {
                            drawTravellingArrow(piece, move, advance.value, cols, cellPx, color)
                        } else {
                            drawArrow(piece, cols, cellPx, color)
                        }
                    }
                }
        ) {
            // Semantics only: invisible, one per arrow, sitting on its head cell. Taps anywhere along an
            // arrow are handled by the board's own pointerInput; these exist so a screen reader has
            // something to find and activate.
            //
            // `key` matters: without it, clearing an arrow shifts every later Box's identity onto a
            // different arrow, and any interaction state they hold - a half-finished ripple - replays on
            // an unrelated head. `indication = null` then makes sure there is no ripple to leak, since
            // the board draws its own feedback and a stray square flashing over a cell reads as a bug.
            for (piece in game.remaining) {
                key(piece.id) {
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
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onTapArrow(piece.id) }
                            .clearAndSetSemantics { contentDescription = description }
                    )
                }
            }
            // Redirectors are not interactive, but a screen reader still has to know they are there.
            val redirector = stringResource(R.string.cd_redirector)
            for (index in game.puzzle.mirrors.keys) {
                key(index) {
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
 * One arrow part-way along its route.
 *
 * Cell `i` of the piece sits at route position `i + advance`, so the body follows wherever the head went
 * and turns corners with it. Fractional advance interpolates between the two positions either side, which
 * is what makes the motion continuous rather than a hop per cell.
 *
 * Once the route runs out the piece is leaving the board, so positions past the end are extrapolated from
 * the final step's direction — it slides off the edge instead of stopping dead on it.
 */
private fun DrawScope.drawTravellingArrow(
    piece: ArrowPiece,
    move: ArrowMove,
    advance: Float,
    cols: Int,
    cell: Float,
    color: Color,
) {
    val centres = piece.cells.indices.map { routePoint(move.route, it + advance, cols, cell) }
    // Sampled half a cell further along the route rather than taken from the last two body cells: a
    // one-cell arrow has no "last two", and sampling also keeps the head turning smoothly through a
    // mirror instead of snapping once the body reaches it.
    val headAt = piece.cells.lastIndex + advance
    val ahead = routePoint(move.route, headAt + 0.5f, cols, cell)
    val head = centres.last()
    val sampled = Offset(ahead.x - head.x, ahead.y - head.y)
    // A mirror ring leaves an arrow with no escape path at all, so there is nothing ahead to sample and
    // the arrowhead would collapse to a dot. Fall back to the direction it is painted pointing.
    val heading = if (kotlin.math.hypot(sampled.x, sampled.y) > 0.01f) sampled
    else Offset(piece.direction.dCol.toFloat(), piece.direction.dRow.toFloat())
    drawArrowShape(centres, heading, cell, color)
}

/**
 * Where route position [at] falls in pixels, interpolating between whole cells.
 *
 * Beyond the last cell it keeps going in the direction of the final step, so an exiting arrow carries on
 * off the board rather than piling up on the edge.
 */
private fun routePoint(route: List<Int>, at: Float, cols: Int, cell: Float): Offset {
    fun centre(index: Int) = Offset((index % cols + 0.5f) * cell, (index / cols + 0.5f) * cell)

    val last = route.size - 1
    if (at <= 0f) return centre(route[0])
    if (at >= last) {
        val end = centre(route[last])
        val previous = centre(route[(last - 1).coerceAtLeast(0)])
        val overshoot = at - last
        return Offset(
            end.x + (end.x - previous.x) * overshoot,
            end.y + (end.y - previous.y) * overshoot,
        )
    }
    val low = at.toInt()
    val t = at - low
    val from = centre(route[low])
    val to = centre(route[low + 1])
    return Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
}

/**
 * One arrow: its body as a single joined stroke, and an arrowhead at the head.
 *
 * The stroke runs through cell centres, so a turn in the polyline becomes a rounded corner. The tip
 * reaches past the head's centre by [HEAD_REACH] to leave room for the barbs while staying inside its own
 * cell rather than poking into the next one.
 */
private fun DrawScope.drawArrow(piece: ArrowPiece, cols: Int, cell: Float, color: Color) {
    val centres = piece.cells.map { Offset((it % cols + 0.5f) * cell, (it / cols + 0.5f) * cell) }
    val heading = Offset(piece.direction.dCol.toFloat(), piece.direction.dRow.toFloat())
    drawArrowShape(centres, heading, cell, color)
}

/**
 * Draws the body through [centres] and an arrowhead pointing along [heading].
 *
 * [heading] need not be normalised - it is only used for its direction - which lets the animated path
 * supply the difference between its last two points and get a head that turns with the route.
 */
private fun DrawScope.drawArrowShape(
    centres: List<Offset>,
    heading: Offset,
    cell: Float,
    color: Color,
) {
    val stroke = cell * 0.11f
    val length = kotlin.math.hypot(heading.x, heading.y).takeIf { it > 0.0001f } ?: 1f
    val unit = Offset(heading.x / length, heading.y / length)

    val head = centres.last()
    val tip = Offset(head.x + unit.x * cell * HEAD_REACH, head.y + unit.y * cell * HEAD_REACH)

    val path = Path().apply {
        moveTo(centres.first().x, centres.first().y)
        for (point in centres.drop(1)) lineTo(point.x, point.y)
        lineTo(tip.x, tip.y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    // Two barbs angled back from the tip. The perpendicular of a 2D vector is its components swapped
    // with one negated, so no trigonometry is needed even once the heading is diagonal mid-turn.
    val barb = cell * 0.26f
    for (side in intArrayOf(1, -1)) {
        drawLine(
            color = color,
            start = tip,
            end = Offset(
                tip.x + (-unit.x + -unit.y * side) * barb,
                tip.y + (-unit.y + unit.x * side) * barb,
            ),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** How far past the head cell's centre the tip reaches, as a fraction of a cell. */
private const val HEAD_REACH = 0.34f

/** How long the arrow takes per cell of travel. Fast enough not to be a wait, slow enough to follow. */
private const val MILLIS_PER_CELL = 45

/** Floor on the animation, so a one-cell move is still long enough to register. */
private const val MIN_MOVE_MILLIS = 120

private fun cellMillis(cells: Int): Int =
    (cells * MILLIS_PER_CELL).coerceAtLeast(MIN_MOVE_MILLIS)

/** How far a wedged arrow lurches before coming back, in cells. */
private const val NudgeCells = 0.3f

/** How long the arrow stays red at the far end before returning. */
private const val BlockedHoldMillis = 180L

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

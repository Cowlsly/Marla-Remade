package com.vayunmathur.games.unblockjam.platform
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.vayunmathur.games.unblockjam.data.Block
import com.vayunmathur.games.unblockjam.data.Coord
import com.vayunmathur.games.unblockjam.data.Dimension
import com.vayunmathur.games.unblockjam.data.LevelData

private fun findMovementRange(
    block: Block,
    otherBlocks: List<Block>,
    levelData: LevelData,
    isMainBlock: Boolean,
    isHorizontal: Boolean
): IntRange {
    fun isOccupied(x: Int, y: Int) = otherBlocks.any {
        x in it.position.x until it.position.x + it.dimension.width &&
        y in it.position.y until it.position.y + it.dimension.height
    }

    val pos = if (isHorizontal) block.position.x else block.position.y
    val size = if (isHorizontal) block.dimension.width else block.dimension.height
    val boardLimit = if (isHorizontal) levelData.dimension.width else levelData.dimension.height
    val perpPos = if (isHorizontal) block.position.y else block.position.x
    val perpSize = if (isHorizontal) block.dimension.height else block.dimension.width

    fun isClearAt(primary: Int) = (perpPos until perpPos + perpSize).all { p ->
        if (isHorizontal) !isOccupied(primary, p) else !isOccupied(p, primary)
    }

    var min = pos
    while (min > 0 && isClearAt(min - 1)) min--

    var max = pos
    while (max + size < boardLimit && isClearAt(max + size)) max++

    if (isHorizontal && isMainBlock && block.position.y == levelData.exit.y) {
        val pathClear = (max + size until levelData.dimension.width).all { x ->
            !isOccupied(x, block.position.y)
        }
        if (pathClear) max = levelData.exit.x
    }

    return min..max
}

fun Modifier.blockDragGestures(
    block: Block,
    levelData: LevelData,
    isLevelWon: Boolean,
    cellWidth: Dp,
    cellHeight: Dp,
    isMainBlock: Boolean,
    onLevelWon: () -> Unit,
    onLevelChanged: (LevelData) -> Unit,
    index: Int,
    offsetXProvider: () -> Dp,
    offsetYProvider: () -> Dp,
    offsetXUpdater: (Dp) -> Unit,
    offsetYUpdater: (Dp) -> Unit
): Modifier {
    val isHorizontal = block.dimension.width > block.dimension.height
    return pointerInput(block, levelData, isLevelWon) {
        if (isLevelWon || block.fixed) return@pointerInput

        var minOffset = 0.dp
        var maxOffset = 0.dp

        detectDragGestures(
            onDragStart = {
                val range = findMovementRange(block, levelData.blocks - block, levelData, isMainBlock, isHorizontal)
                val cellSize = if (isHorizontal) cellWidth else cellHeight
                minOffset = cellSize * range.first
                maxOffset = cellSize * range.last
            },
            onDragEnd = {
                val newX: Int
                val newY: Int
                if (isHorizontal) {
                    newX = (offsetXProvider() / cellWidth).roundToInt()
                    newY = block.position.y
                } else {
                    newX = block.position.x
                    newY = (offsetYProvider() / cellHeight).roundToInt()
                }

                val newBlock = block.copy(position = Coord(newX, newY))
                when {
                    isMainBlock && block.position.y == levelData.exit.y && newX >= levelData.exit.x -> onLevelWon()
                    newBlock.position != block.position && isMoveValid(newBlock, levelData.blocks - block, levelData.dimension) -> {
                        val newBlocks = levelData.blocks.toMutableList()
                        newBlocks[index] = newBlock
                        onLevelChanged(levelData.copy(blocks = newBlocks, lastMovedBlockIndex = index))
                    }
                    else -> {
                        offsetXUpdater(cellWidth * block.position.x)
                        offsetYUpdater(cellHeight * block.position.y)
                    }
                }
            },
            onDrag = { change, dragAmount ->
                change.consume()
                if (isHorizontal) {
                    val newOffsetX = (offsetXProvider() + dragAmount.x.toDp()).coerceIn(minOffset, maxOffset)
                    offsetXUpdater(newOffsetX)
                    if (isMainBlock && block.position.y == levelData.exit.y &&
                        (newOffsetX / cellWidth).roundToInt() + block.dimension.width - 1 >= levelData.exit.x) {
                        onLevelWon()
                    }
                } else {
                    offsetYUpdater((offsetYProvider() + dragAmount.y.toDp()).coerceIn(minOffset, maxOffset))
                }
            }
        )
    }
}

fun isMoveValid(movedBlock: Block, otherBlocks: List<Block>, dimension: Dimension): Boolean {
    if (movedBlock.position.x < 0 || movedBlock.position.y < 0) return false
    if (movedBlock.position.x + movedBlock.dimension.width > dimension.width) return false
    if (movedBlock.position.y + movedBlock.dimension.height > dimension.height) return false

    return otherBlocks.none { other ->
        movedBlock.position.x < other.position.x + other.dimension.width &&
        movedBlock.position.x + movedBlock.dimension.width > other.position.x &&
        movedBlock.position.y < other.position.y + other.dimension.height &&
        movedBlock.position.y + movedBlock.dimension.height > other.position.y
    }
}

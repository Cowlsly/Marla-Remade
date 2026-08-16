package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.data.ChapterId
import com.vayunmathur.games.logicgate.data.ChipLibrary
import com.vayunmathur.games.logicgate.data.Levels
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text as LibText

private sealed class TimelineItem {
    data class ChapterHeader(val chapterId: ChapterId) : TimelineItem()
    data class LevelRow(val levelIds: List<String>) : TimelineItem()
}

private fun buildTimelineItems(): List<TimelineItem> {
    val items = mutableListOf<TimelineItem>()
    var lastChapter: ChapterId? = null
    for (row in Levels.timelineRows) {
        if (row.isEmpty()) continue
        val firstId = row.first()
        val chapter = Levels.byId[firstId]?.chapter ?: continue
        if (chapter != lastChapter) {
            items.add(TimelineItem.ChapterHeader(chapter))
            lastChapter = chapter
        }
        items.add(TimelineItem.LevelRow(row))
    }
    return items
}

/**
 * The level map. Takes the completed set and two callbacks rather than the ViewModel and
 * back stack, so it can be rendered from a `@Preview` — see `src/screenshotTest`, which is
 * where the store listing images come from. Everything else it draws is derived from the
 * static [Levels] table.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressionScreen(
    completed: Set<String>,
    onOpenLevel: (String) -> Unit = {},
    onOpenGameCenter: () -> Unit = {},
) {
    val available = Levels.availableLevels(completed)
    val timelineItems = buildTimelineItems()
    AppScaffold(
        title = { LibText(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
        actions = {
            IconButton(onClick = onOpenGameCenter) {
                Icon(painterResource(id = android.R.drawable.btn_star_big_on), contentDescription = stringResource(R.string.cd_achievements))
            }
        }
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(paddingValues).background(MaterialTheme.colorScheme.background)
        ) {
            val isCompact = maxWidth < 600.dp
            val nodeSize: Dp = if (isCompact) MobileDimens.progNodePhone else MobileDimens.progNodeTablet
            val rowHeight: Dp = if (isCompact) MobileDimens.progRowPhone else MobileDimens.progRowTablet
            val sideOffset: Dp = nodeSize / 2 + 32.dp
            val connectorH: Dp = MobileDimens.progConnectorH
            val chapterH: Dp = MobileDimens.progChapterContainer
            val scroll = rememberScrollState()

            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                LibText(
                    stringResource(R.string.start_with_nand_end_with_a_computer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                LibText(
                    stringResource(R.string.tap_to_play_lines_show_dependencies_36_l),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(modifier = Modifier.height(20.dp))
                for (idx in timelineItems.indices) {
                    val item = timelineItems[idx]
                    when (item) {
                        is TimelineItem.ChapterHeader -> {
                            if (idx > 0) {
                                val prev = timelineItems[idx - 1]
                                if (prev is TimelineItem.LevelRow) MergeIntoChapterConnector(prev, sideOffset, connectorH, completed)
                            }
                            ChapterDivider(
                                item.chapterId,
                                Levels.chapters.find { it.id == item.chapterId }?.levelIds?.count { it in completed } ?: 0,
                                Levels.chapters.find { it.id == item.chapterId }?.levelIds?.size ?: 0,
                                Modifier.fillMaxWidth().height(chapterH).padding(horizontal = 4.dp, vertical = 8.dp)
                            )
                            if (idx + 1 < timelineItems.size) {
                                val next = timelineItems[idx + 1]
                                if (next is TimelineItem.LevelRow) BranchOutOfChapterConnector(next, sideOffset, connectorH, completed)
                            }
                        }
                        is TimelineItem.LevelRow -> {
                            LevelRowContent(item, completed, available, nodeSize, rowHeight) { lvlId ->
                                if (lvlId in completed || lvlId in available) onOpenLevel(lvlId)
                            }
                            if (idx + 1 < timelineItems.size) {
                                val next = timelineItems[idx + 1]
                                if (next is TimelineItem.LevelRow) RowToRowConnector(item, next, sideOffset, connectorH, completed)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        LibText(
                            stringResource(R.string.completed, completed.size, Levels.all.size),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF7FD8BE)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth(0.8f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF2A3A4A))
                        ) {
                            val progress = if (Levels.all.isNotEmpty()) completed.size.toFloat() / Levels.all.size.toFloat() else 0f
                            Box(
                                modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(Color(0xFF7FD8BE))
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun ChapterDivider(chapterId: ChapterId, completedCount: Int, totalCount: Int, modifier: Modifier = Modifier) {
    val chapter = Levels.chapters.find { it.id == chapterId } ?: return
    Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
        Box(modifier = Modifier.fillMaxWidth().height(MobileDimens.progChapterBar).clip(RoundedCornerShape(8.dp)).background(Color(0xFF7FD8BE).copy(alpha = 0.95f)))
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            LibText(
                text = "${chapter.name.uppercase()} — ${chapter.desc}",
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.2.sp,
                color = Color(0xFF7FD8BE),
                modifier = Modifier.padding(start = 4.dp).weight(1f)
            )
            LibText(text = "$completedCount/$totalCount", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
        }
    }
}

@Composable
private fun LevelRowContent(
    row: TimelineItem.LevelRow,
    completed: Set<String>,
    available: Set<String>,
    nodeSize: Dp,
    rowHeight: Dp,
    onClickLevel: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(rowHeight),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (row.levelIds.size == 1) LevelNode(row.levelIds[0], row.levelIds[0] in completed, row.levelIds[0] in available, nodeSize, onClickLevel)
        else row.levelIds.forEachIndexed { idx, lvlId ->
            LevelNode(lvlId, lvlId in completed, lvlId in available, nodeSize, onClickLevel)
            if (idx == 0) Spacer(modifier = Modifier.width(MobileDimens.progSpacingDual))
        }
    }
}

@Composable
private fun LevelNode(levelId: String, isCompleted: Boolean, isAvailable: Boolean, size: Dp, onClick: (String) -> Unit) {
    val def = Levels.byId[levelId] ?: return
    val isLocked = !isCompleted && !isAvailable
    val bg = when { isCompleted -> Color(0xFF1E3A2F); isAvailable -> Color(0xFF1B2E41); else -> Color(0xFF242A33) }
    val borderCol = when { isCompleted -> Color(0xFF22C55E); isAvailable -> Color(0xFF7FD8BE); else -> Color(0xFF4B5563) }
    val borderW = if (isAvailable) 3.5.dp else 2.dp
    val targetDef = try { ChipLibrary.get(def.targetChipId) } catch (_: Exception) { null }
    val busW = targetDef?.dominantBusWidth() ?: def.inputWidths.maxOrNull() ?: 1
    val busColor = when (busW) { 4 -> Color(0xFFF59E0B); 8 -> Color(0xFF60A5FA); else -> Color(0xFF7ED8B6) }
    Column(
        modifier = Modifier
            .widthIn(min = 108.dp)
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !isLocked) { onClick(levelId) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(bg)
                .border(borderW, borderCol, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                LibText(
                    text = def.displayName.take(10),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = when { isLocked -> Color(0xFF6B7280); isCompleted -> Color(0xFF86EFAC); else -> Color.White }
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (busW > 1) Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(busColor))
                    when {
                        isLocked -> LibText(stringResource(R.string.lock), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6B7280))
                        isCompleted -> LibText(stringResource(R.string.done), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF22C55E))
                        else -> LibText(stringResource(R.string.play), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7FD8BE))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        LibText(
            text = def.displayName,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isLocked) Color(0xFF6B7280) else MaterialTheme.colorScheme.onBackground
        )
        if (busW > 1) {
            Spacer(modifier = Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(busColor))
                LibText(text = "${busW}b ${if (busW == 8) "BUS" else "nibble"}", fontSize = 11.sp, color = busColor.copy(alpha = 0.9f))
            }
        }
    }
}

@Composable
private fun RowToRowConnector(fromRow: TimelineItem.LevelRow, toRow: TimelineItem.LevelRow, sideOffset: Dp, height: Dp, completed: Set<String>) {
    Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
        val cx = center.x; val leftX = cx - sideOffset.toPx(); val rightX = cx + sideOffset.toPx()
        fun xFor(id: String, row: TimelineItem.LevelRow) = if (row.levelIds.size == 1) cx else if (row.levelIds[0] == id) leftX else rightX
        for (toId in toRow.levelIds) {
            val def = Levels.byId[toId] ?: continue; val toX = xFor(toId, toRow)
            for (pr in def.prereqs) if (pr in fromRow.levelIds) {
                val fromX = xFor(pr, fromRow)
                drawLine(if (pr in completed) Color(0xFF7FD8BE) else Color(0xFF374151), Offset(fromX, 0f), Offset(toX, size.height), strokeWidth = 8f)
            }
        }
    }
}

@Composable
private fun MergeIntoChapterConnector(fromRow: TimelineItem.LevelRow, sideOffset: Dp, height: Dp, completed: Set<String>) {
    Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
        val cx = center.x; val leftX = cx - sideOffset.toPx(); val rightX = cx + sideOffset.toPx()
        fun xFor(id: String) = if (fromRow.levelIds.size == 1) cx else if (fromRow.levelIds[0] == id) leftX else rightX
        for (id in fromRow.levelIds) drawLine(if (id in completed) Color(0xFF7FD8BE) else Color(0xFF374151), Offset(xFor(id), 0f), Offset(cx, size.height), strokeWidth = 8f)
    }
}

@Composable
private fun BranchOutOfChapterConnector(toRow: TimelineItem.LevelRow, sideOffset: Dp, height: Dp, completed: Set<String>) {
    Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
        val cx = center.x; val leftX = cx - sideOffset.toPx(); val rightX = cx + sideOffset.toPx()
        fun xFor(id: String) = if (toRow.levelIds.size == 1) cx else if (toRow.levelIds[0] == id) leftX else rightX
        for (id in toRow.levelIds) {
            val def = Levels.byId[id] ?: continue
            val unlocked = def.prereqs.all { it in completed }
            drawLine(if (unlocked) Color(0xFF7FD8BE) else Color(0xFF374151), Offset(cx, 0f), Offset(xFor(id), size.height), strokeWidth = 8f)
        }
    }
}

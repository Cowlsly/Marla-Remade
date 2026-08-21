package com.vayunmathur.photos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.ButtonGroup
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.photos.data.CurveChannel
import com.vayunmathur.photos.data.CurveControlPoint
import com.vayunmathur.photos.data.CurvesAdjustment
import com.vayunmathur.photos.data.interpolateSpline

@Composable
fun CurvesPanel(
    curves: CurvesAdjustment,
    selectedChannel: CurveChannel,
    onChannelSelected: (CurveChannel) -> Unit,
    onCurvesChanged: (CurvesAdjustment) -> Unit,
) {
    val channelPoints = when (selectedChannel) {
        CurveChannel.Combined -> curves.combined
        CurveChannel.Red -> curves.red
        CurveChannel.Green -> curves.green
        CurveChannel.Blue -> curves.blue
    }
    val channelColor = when (selectedChannel) {
        CurveChannel.Combined -> Color.White
        CurveChannel.Red -> Color.Red
        CurveChannel.Green -> Color.Green
        CurveChannel.Blue -> Color.Blue
    }
    val lut = remember(channelPoints) { interpolateSpline(channelPoints, 100) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(8.dp),
    ) {
        ButtonGroup(Modifier.fillMaxWidth()) {
            CurveChannel.entries.forEach { channel ->
                toggleableItem(
                    checked = selectedChannel == channel,
                    label = channel.name,
                    onCheckedChange = { onChannelSelected(channel) },
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(8.dp)
                .pointerInput(selectedChannel, channelPoints) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val x = (offset.x / size.width).coerceIn(0f, 1f)
                            val y = (1f - offset.y / size.height).coerceIn(0f, 1f)
                            val nearIdx = channelPoints.indexOfFirst { p ->
                                kotlin.math.abs(p.x - x) < 0.05f && kotlin.math.abs(p.y - y) < 0.1f
                            }
                            if (nearIdx == -1) {
                                val newPoints = (channelPoints + CurveControlPoint(x, y)).sortedBy { it.x }
                                onCurvesChanged(updateChannel(curves, selectedChannel, newPoints))
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val x = (change.position.x / size.width).coerceIn(0f, 1f)
                            val y = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                            val nearIdx = channelPoints.indexOfFirst { p ->
                                kotlin.math.abs(p.x - x) < 0.08f
                            }
                            if (nearIdx != -1) {
                                val newPoints = channelPoints.toMutableList()
                                newPoints[nearIdx] = CurveControlPoint(
                                    channelPoints[nearIdx].x,
                                    y
                                )
                                onCurvesChanged(updateChannel(curves, selectedChannel, newPoints))
                            }
                        },
                    )
                },
        ) {
            val w = size.width
            val h = size.height
            for (i in 1..3) {
                val pos = i / 4f
                drawLine(Color.Gray.copy(alpha = 0.3f), Offset(pos * w, 0f), Offset(pos * w, h))
                drawLine(Color.Gray.copy(alpha = 0.3f), Offset(0f, pos * h), Offset(w, pos * h))
            }
            drawLine(Color.Gray.copy(alpha = 0.2f), Offset(0f, h), Offset(w, 0f))

            val path = Path()
            for (i in lut.indices) {
                val x = i.toFloat() / (lut.size - 1) * w
                val y = (1f - lut[i]) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, channelColor, style = Stroke(width = 2.dp.toPx()))

            channelPoints.forEach { point ->
                drawCircle(
                    channelColor,
                    radius = 6.dp.toPx(),
                    center = Offset(point.x * w, (1f - point.y) * h),
                )
                drawCircle(
                    Color.Black,
                    radius = 6.dp.toPx(),
                    center = Offset(point.x * w, (1f - point.y) * h),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
    }
}

private fun updateChannel(
    curves: CurvesAdjustment,
    channel: CurveChannel,
    points: List<CurveControlPoint>,
): CurvesAdjustment = when (channel) {
    CurveChannel.Combined -> curves.copy(combined = points)
    CurveChannel.Red -> curves.copy(red = points)
    CurveChannel.Green -> curves.copy(green = points)
    CurveChannel.Blue -> curves.copy(blue = points)
}

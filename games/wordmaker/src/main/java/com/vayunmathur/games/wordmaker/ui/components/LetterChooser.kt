package com.vayunmathur.games.wordmaker.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.wordmaker.R
import com.vayunmathur.games.wordmaker.ui.ChooserLetter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun LetterChooser(
    letters: List<ChooserLetter>,
    tapToSpell: Boolean,
    onShuffle: () -> Unit,
    onWordSubmitted: suspend CoroutineScope.(String, List<Int>) -> Unit,
    onWordBoxPositioned: (Offset) -> Unit,
    onLetterPositioned: (id: Int, offset: Offset) -> Unit,
    wordShakeTranslation: Float
) {
    val coroutineScope = rememberCoroutineScope()

    var selectedLettersIndices by remember(letters) { mutableStateOf(listOf<Int>()) }
    val formedWord = selectedLettersIndices.map { letters[it].char }.joinToString("")
    var dragStartOffset by remember(letters) { mutableStateOf(Offset.Zero) }
    var currentDragPosition by remember(letters) { mutableStateOf<Offset?>(null) }

    val density = LocalDensity.current
    val letterCircleRadius = with(density) { 35.dp.toPx() }
    val boxSizePx = with(density) { 250.dp.toPx() }
    val boxCenter = Offset(boxSizePx / 2, boxSizePx / 2)

    val angleStep = 2 * Math.PI / letters.size.toDouble()
    val radius = 85.dp
    val radiusPx = with(density) { radius.toPx() }
    val letterCenters = remember(letters, boxCenter, radiusPx) {
        List(letters.size) { index ->
            val angle = angleStep * index - (Math.PI / 2)
            boxCenter + Offset(cos(angle).toFloat() * radiusPx, sin(angle).toFloat() * radiusPx)
        }
    }

    fun getLetterAtArc(position: Offset): Int {
        val relative = position - boxCenter

        val angle = atan2(relative.y, relative.x)
        var normalizedAngle = angle + Math.PI / 2
        while (normalizedAngle < 0) normalizedAngle += 2 * Math.PI
        while (normalizedAngle >= 2 * Math.PI) normalizedAngle -= 2 * Math.PI

        return (normalizedAngle / angleStep).roundToInt() % letters.size
    }

    fun getLetterAtCircle(position: Offset): Int? {
        for (i in letterCenters.indices) {
            val center = letterCenters[i]
            if (distance(position, center) <= letterCircleRadius) {
                return i
            }
        }
        return null
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SurfaceText(
            Modifier
                .padding(bottom = 20.dp)
                .graphicsLayer(
                    alpha = if (selectedLettersIndices.isNotEmpty()) 1f else 0f,
                    translationX = wordShakeTranslation
                ),
            RoundedCornerShape(8.dp), MaterialTheme.colorScheme.primaryContainer,
            formedWord.ifEmpty { " " },
            Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .onGloballyPositioned { onWordBoxPositioned(it.localToRoot(Offset.Zero)) },
            FontWeight.Bold,
            32.sp,
            null
        )

        Row(verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier.width(72.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (tapToSpell && selectedLettersIndices.isNotEmpty()) {
                    FilledIconButton(onClick = {
                        selectedLettersIndices = selectedLettersIndices.dropLast(1)
                    }) {
                        Icon(painterResource(R.drawable.backspace_24px), contentDescription = stringResource(R.string.cd_backspace))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FilledIconButton(onClick = {
                        if (selectedLettersIndices.isNotEmpty()) {
                            val word = selectedLettersIndices.map { letters[it].char }.joinToString("")
                            val ids = selectedLettersIndices.map { letters[it].id }
                            coroutineScope.launch {
                                onWordSubmitted(word, ids)
                                selectedLettersIndices = emptyList()
                            }
                        }
                    }) {
                        Icon(painterResource(R.drawable.keyboard_return_24px), contentDescription = stringResource(R.string.cd_submit))
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(250.dp)
                    .onGloballyPositioned {
                        dragStartOffset = it.localToRoot(Offset.Zero)
                    }
                    .pointerInput(letters) {
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                currentDragPosition = startOffset
                                selectedLettersIndices = listOf(getLetterAtArc(startOffset))
                            },
                            onDrag = { change, _ ->
                                currentDragPosition = change.position
                                getLetterAtCircle(change.position)?.let { idx ->
                                    if (idx !in selectedLettersIndices) {
                                        selectedLettersIndices = selectedLettersIndices + idx
                                    } else if (selectedLettersIndices.size > 1 && idx == selectedLettersIndices[selectedLettersIndices.size - 2]) {
                                        selectedLettersIndices = selectedLettersIndices.dropLast(1)
                                    }
                                }
                            },
                            onDragEnd = {
                                coroutineScope.launch {
                                    if (selectedLettersIndices.isNotEmpty()) {
                                        onWordSubmitted(
                                            selectedLettersIndices.map { letters[it].char }.joinToString(""),
                                            selectedLettersIndices.map { letters[it].id }
                                        )
                                    }
                                    selectedLettersIndices = emptyList()
                                    currentDragPosition = null
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                val primaryColor = MaterialTheme.colorScheme.primary
                Canvas(modifier = Modifier.fillMaxSize()) {
                    selectedLettersIndices.zipWithNext { a, b ->
                        drawLine(primaryColor, letterCenters[a], letterCenters[b], 10f, cap = StrokeCap.Round)
                    }
                    val lastLetter = selectedLettersIndices.lastOrNull()
                    if (lastLetter != null && currentDragPosition != null) {
                        drawLine(primaryColor, letterCenters[lastLetter], currentDragPosition!!, 10f, cap = StrokeCap.Round)
                    }
                }
                Surface(
                    Modifier.fillMaxSize(0.9f),
                    CircleShape,
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                ) {}

                letters.forEachIndexed { index, chooserLetter ->
                    key(chooserLetter.id) {
                        val angle = angleStep * index - (Math.PI / 2)
                        val targetX = (cos(angle) * radius.value).dp
                        val targetY = (sin(angle) * radius.value).dp

                        val x by animateDpAsState(targetX, label = "x")
                        val y by animateDpAsState(targetY, label = "y")

                        SurfaceText(Modifier
                            .align(Alignment.Center)
                            .offset { IntOffset(x.roundToPx(), y.roundToPx()) }
                            .onGloballyPositioned { coordinates ->
                                onLetterPositioned(chooserLetter.id, coordinates.localToRoot(Offset.Zero))
                            }
                            .then(if (tapToSpell) Modifier.clickable {
                                if (selectedLettersIndices.lastOrNull() == index) {
                                    selectedLettersIndices = selectedLettersIndices.dropLast(1)
                                } else if (index !in selectedLettersIndices) {
                                    selectedLettersIndices = selectedLettersIndices + index
                                }
                            } else Modifier),
                            CircleShape,
                            if (index in selectedLettersIndices) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                            chooserLetter.char.toString(),
                            Modifier.padding(1.dp),
                            FontWeight.Bold,
                            42.sp,
                            70.dp
                        )
                    }
                }
            }
            FilledIconButton(onClick = onShuffle) {
                Icon(painterResource(R.drawable.ic_shuffle), contentDescription = stringResource(R.string.cd_shuffle))
            }
        }
    }
}

private fun distance(a: Offset, b: Offset): Float = (a - b).getDistance()
package com.vayunmathur.library.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text

/**
 * A fixed-size titled panel for the readouts flanking a game board — level number, move count.
 *
 * Fixed rather than intrinsic so a row of them stays aligned as their contents change width: a move
 * counter ticking from 9 to 10 should not shift the box beside it.
 */
@Composable
fun GameInfoBox(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.size(width = BoxWidth, height = BoxHeight),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceAround,
        ) {
            Text(text = title, fontSize = 16.sp)
            content()
        }
    }
}

private val BoxWidth = 150.dp
private val BoxHeight = 120.dp

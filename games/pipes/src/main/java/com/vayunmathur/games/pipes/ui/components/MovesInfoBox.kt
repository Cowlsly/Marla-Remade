package com.vayunmathur.games.pipes.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.pipes.R
import com.vayunmathur.library.ui.Text

@Composable
fun MovesInfoBox(moves: Int, bestScore: Int?, optimalMoves: Int) {
    InfoBox(title = stringResource(R.string.moves)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(text = "$moves", fontSize = 24.sp, fontWeight = FontWeight.Bold); Text(text = "${bestScore ?: "-"} / $optimalMoves", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
    }
}

package com.vayunmathur.games.pipes.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.pipes.R
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

@Composable
fun PuzzleInfoBox(levelIndex: Int, onLevelChange: (Int) -> Unit, isCompleted: Boolean, maxLevelIndex: Int) {
    InfoBox(title = stringResource(R.string.level)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { onLevelChange(levelIndex - 1) }, enabled = levelIndex > 0) { Icon(painterResource(R.drawable.arrow_back_24px), contentDescription = stringResource(R.string.previous_level)) }
            Text(text = "${levelIndex + 1}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = { onLevelChange(levelIndex + 1) }, enabled = levelIndex < maxLevelIndex) { Icon(painterResource(R.drawable.arrow_forward_24px), contentDescription = stringResource(R.string.next_level)) }
        }
        if (isCompleted) { Text(text = stringResource(R.string.completed), color = MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
    }
}

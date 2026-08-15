package com.vayunmathur.games.solitaire.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.solitaire.R
import com.vayunmathur.games.solitaire.data.DrawMode
import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.KlondikeDifficulty
import com.vayunmathur.games.solitaire.ui.displayName
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.TextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameConfigDialog(mode: GameMode, onStart: (GameConfig) -> Unit, onDismiss: () -> Unit) {
    var drawMode by remember { mutableStateOf(DrawMode.DRAW_ONE) }
    var klondikeDifficulty by remember { mutableStateOf(KlondikeDifficulty.REGULAR) }
    var relaxed by remember { mutableStateOf(false) }
    var spiderSuits by remember { mutableStateOf(4) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(mode.displayName()) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (mode) {
                    GameMode.KLONDIKE -> {
                        SingleChoiceSegmentedButtonRow {
                            listOf(DrawMode.DRAW_ONE to R.string.draw_one, DrawMode.DRAW_THREE to R.string.draw_three).forEachIndexed { idx, (value, label) ->
                                SegmentedButton(shape = SegmentedButtonDefaults.itemShape(idx, 2), onClick = { drawMode = value }, selected = drawMode == value) { Text(stringResource(label)) }
                            }
                        }
                        SingleChoiceSegmentedButtonRow {
                            listOf(KlondikeDifficulty.RELAXED to R.string.mode_relaxed, KlondikeDifficulty.REGULAR to R.string.difficulty_regular, KlondikeDifficulty.HARD to R.string.difficulty_hard).forEachIndexed { idx, (value, label) ->
                                SegmentedButton(shape = SegmentedButtonDefaults.itemShape(idx, 3), onClick = { klondikeDifficulty = value }, selected = klondikeDifficulty == value) { Text(stringResource(label)) }
                            }
                        }
                    }
                    GameMode.SPIDER -> {
                        SingleChoiceSegmentedButtonRow {
                            listOf(1 to R.string.difficulty_easy, 2 to R.string.difficulty_medium, 4 to R.string.difficulty_hard).forEachIndexed { idx, (value, label) ->
                                SegmentedButton(shape = SegmentedButtonDefaults.itemShape(idx, 3), onClick = { spiderSuits = value }, selected = spiderSuits == value) { Text(stringResource(label)) }
                            }
                        }
                    }
                    GameMode.PYRAMID -> DifficultyRow(relaxed) { relaxed = it }
                    GameMode.FREECELL -> {}
                }
                Button(onClick = { onStart(GameConfig(drawMode = drawMode, klondikeDifficulty = klondikeDifficulty, relaxed = relaxed, spiderSuits = spiderSuits)) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.new_game)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.back)) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DifficultyRow(relaxed: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        listOf(false to R.string.mode_original, true to R.string.mode_relaxed).forEachIndexed { idx, (value, label) ->
            SegmentedButton(shape = SegmentedButtonDefaults.itemShape(idx, 2), onClick = { onChange(value) }, selected = relaxed == value) { Text(stringResource(label)) }
        }
    }
}

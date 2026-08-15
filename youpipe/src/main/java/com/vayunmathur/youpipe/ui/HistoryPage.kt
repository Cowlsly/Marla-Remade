package com.vayunmathur.youpipe.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.util.YouPipeViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryPage(backStack: NavBackStack<Route>, youPipeViewModel: YouPipeViewModel) {
    val history by youPipeViewModel.historyVideosByRecency.collectAsState()

    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val inSelectionMode = selectedIds.isNotEmpty()

    // Back is the only way out of a selection now that there is no top bar to
    // host a cancel button.
    BackHandler(enabled = inSelectionMode) { selectedIds = emptySet() }

    // No top bar at all: clearing all history lives in Settings, and deleting a
    // selection is a FAB, so the list keeps the full height of the screen.
    Scaffold(
        floatingActionButton = {
            if (inSelectionMode) {
                FloatingActionButton(onClick = {
                    youPipeViewModel.deleteHistoryVideos(selectedIds.toList())
                    selectedIds = emptySet()
                }) {
                    IconDelete()
                }
            }
        },
    ) { paddingValues ->
        LazyColumn(Modifier.padding(paddingValues)) {
            items(history, key = { it.id }) { historyItem ->
                val isSelected = historyItem.id in selectedIds
                VideoItem(
                    backStack, youPipeViewModel, historyItem.videoItem, true,
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            if (inSelectionMode) {
                                selectedIds = if (isSelected) selectedIds - historyItem.id
                                else selectedIds + historyItem.id
                            } else {
                                backStack.add(Route.VideoPage(historyItem.videoItem.videoID))
                            }
                        },
                        onLongClick = {
                            selectedIds = selectedIds + historyItem.id
                        }
                    ),
                    backupOnClick = false,
                    trailingContent = if (inSelectionMode) {
                        {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    selectedIds = if (isSelected) selectedIds - historyItem.id
                                    else selectedIds + historyItem.id
                                }
                            )
                        }
                    } else null,
                )
            }
        }
    }
}

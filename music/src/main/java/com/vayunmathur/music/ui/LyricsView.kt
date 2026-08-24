package com.vayunmathur.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.music.R
import com.vayunmathur.music.platform.LyricLine
import com.vayunmathur.music.platform.Lyrics
@Composable
fun LyricsView(lyrics: Lyrics, currentIndex: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp)
    ) {
        when (lyrics) {
            Lyrics.None -> Text(
                stringResource(R.string.no_lyrics_available),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
                textAlign = TextAlign.Center
            )
            // Nothing to highlight and nothing to scroll to, so this is a block of text that the
            // user reads at their own pace. Previously these files read as "no lyrics available".
            is Lyrics.Plain -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 40.dp)
            ) {
                item {
                    Text(
                        text = lyrics.text,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            is Lyrics.Timed -> TimedLyrics(lyrics.lines, currentIndex)
        }
    }
}
@Composable
private fun TimedLyrics(lines: List<LyricLine>, currentIndex: Int) {
    val listState = rememberLazyListState()
    // Auto-scroll to current lyric
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            listState.animateScrollToItem(currentIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 40.dp)
    ) {
        itemsIndexed(lines) { index, line ->
            val isCurrent = index == currentIndex
            Text(
                text = line.text,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    fontSize = if (isCurrent) 22.sp else 18.sp
                ),
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

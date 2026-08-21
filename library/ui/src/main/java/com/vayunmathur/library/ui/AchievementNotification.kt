package com.vayunmathur.library.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.R
import com.vayunmathur.library.util.Achievement
import kotlinx.coroutines.delay

@Composable
fun AchievementNotification(
    achievement: Achievement,
    onDismiss: () -> Unit
) {
    val visibleState = remember { MutableTransitionState(initialState = false) }

    LaunchedEffect(achievement) {
        visibleState.targetState = true
        delay(8000)
        visibleState.targetState = false
    }
    // Notify the caller only once the exit transition has fully played out.
    LaunchedEffect(visibleState.isIdle) {
        if (visibleState.isIdle && !visibleState.currentState) {
            onDismiss()
        }
    }

    AnimatedVisibility(
        visibleState = visibleState,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier.padding(16.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                // Swipe up on the card to dismiss it early. Dragging up past the
                // threshold plays the same exit animation as the auto-dismiss.
                .pointerInput(Unit) {
                    val threshold = 48.dp.toPx()
                    var totalDrag = 0f
                    detectVerticalDragGestures(
                        onDragEnd = { totalDrag = 0f },
                        onDragCancel = { totalDrag = 0f },
                    ) { change, dragAmount ->
                        change.consume()
                        totalDrag += dragAmount
                        if (totalDrag <= -threshold) {
                            visibleState.targetState = false
                        }
                    }
                },
            shape = MaterialTheme.shapes.largeIncreased,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = android.R.drawable.btn_star_big_on), // Placeholder icon
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = Color(0xFFFFD700)
                )
                Column(
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.achievement_unlocked),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = achievement.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = achievement.description,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

package com.vayunmathur.communicate.notifications

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.ui.ConversationScreen
import com.vayunmathur.library.ui.DynamicTheme

/**
 * The expanded contents of a conversation bubble. Kept separate from [com.vayunmathur.communicate.MainActivity]
 * because a bubble host must be embeddable and document-launched, and reuses the normal
 * [ConversationScreen] so a bubbled thread looks and behaves like the in-app one.
 */
class BubbleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val line = intent.getStringExtra(ConversationSpace.EXTRA_LINE)
            ?.let { runCatching { CommunicateLine.valueOf(it) }.getOrNull() }
            ?: CommunicateLine.Sim
        val address = intent.getStringExtra(ConversationSpace.EXTRA_ADDRESS).orEmpty()
        val remoteId = intent.getStringExtra(ConversationSpace.EXTRA_REMOTE_ID)
        val threadId = intent.getLongExtra(ConversationSpace.EXTRA_THREAD_ID, -1L)
        val isGroup = intent.getBooleanExtra(ConversationSpace.EXTRA_IS_GROUP, false)
        val title = intent.getStringExtra(ConversationSpace.EXTRA_TITLE)
        val subscriptionId = intent.getIntExtra(ConversationSpace.EXTRA_SUB_ID, -1).takeIf { it >= 0 }

        setContent {
            DynamicTheme {
                ConversationScreen(
                    threadId = threadId,
                    address = address,
                    line = line,
                    remoteId = remoteId,
                    subscriptionId = subscriptionId,
                    isGroup = isGroup,
                    groupTitle = title,
                    onBack = { finish() },
                )
            }
        }
    }
}

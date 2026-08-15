package com.vayunmathur.euicc.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import com.vayunmathur.euicc.data.Notification
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

@Composable
fun NotificationsSection(
    notifications: List<Notification>,
    onRemove: (Notification) -> Unit,
) {
    SectionCard(title = "Notifications") {
        if (notifications.isEmpty()) {
            Text("No pending notifications.")
            return@SectionCard
        }
        for (note in notifications) {
            ListItem(
                headlineContent = { Text("#" + note.seqNumber + " \u00b7 " + note.operation) },
                supportingContent = {
                    Text(note.address, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = {
                    TextButton(onClick = { onRemove(note) }) { Text("Remove") }
                },
            )
        }
    }
}

package com.vayunmathur.web.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.Text
import com.vayunmathur.web.R
import com.vayunmathur.web.platform.BrowserUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkContextMenu(
    url: String,
    onDismiss: () -> Unit,
    onCopyLink: () -> Unit,
    onShareLink: () -> Unit,
    onOpenInNewTab: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                Text(
                    text = BrowserUtils.prettyUrl(url).ifBlank { url },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.copy_link)) },
                leadingContent = { IconCopy() },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onCopyLink()
                        onDismiss()
                    },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.share_link)) },
                leadingContent = { IconShare() },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onShareLink()
                        onDismiss()
                    },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.open_in_new_tab)) },
                leadingContent = { IconAdd() },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onOpenInNewTab()
                        onDismiss()
                    },
            )
        }
    }
}

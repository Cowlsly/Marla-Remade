package com.vayunmathur.auto.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.auto.R
import com.vayunmathur.auto.platform.AutoConnectionState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior

/** Whether a car is attached, and what to do if one is not. */
@Composable
fun AutoScreen(state: AutoConnectionState) {
    val scrollBehavior = appBarScrollBehavior()
    AppScaffold(
        title = stringResource(R.string.app_name),
        scrollBehavior = scrollBehavior,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.connection)) },
                    supportingContent = { Text(state.describe()) },
                )
            }
            Text(
                text = stringResource(R.string.connect_hint),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun AutoConnectionState.describe(): String = when (this) {
    AutoConnectionState.Disconnected -> stringResource(R.string.state_disconnected)
    AutoConnectionState.Connecting -> stringResource(R.string.state_connecting)
    is AutoConnectionState.Projecting -> stringResource(R.string.state_projecting, carName)
    AutoConnectionState.Rejected -> stringResource(R.string.state_rejected)
}

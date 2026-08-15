package com.vayunmathur.games.wordmaker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.games.wordmaker.R
import com.vayunmathur.games.wordmaker.platform.WordMakerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(viewModel: WordMakerViewModel, onBack: () -> Unit) {
    val tapToSpell by viewModel.tapToSpell.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(UiR.string.settings)) },
                navigationIcon = {
                    IconNavigation(onBack)
                },
                actions = {
                    com.vayunmathur.library.ui.BackupButtons(
                        datastoreNames = listOf("settings")
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tap_to_spell), style = com.vayunmathur.library.ui.MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.tap_to_spell_description), style = com.vayunmathur.library.ui.MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = tapToSpell,
                    onCheckedChange = { viewModel.setTapToSpell(it) }
                )
            }
        }
    }
}

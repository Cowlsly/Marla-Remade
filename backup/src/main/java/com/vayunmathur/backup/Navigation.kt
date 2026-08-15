package com.vayunmathur.backup

import androidx.compose.runtime.Composable
import com.vayunmathur.backup.platform.BackupViewModel
import com.vayunmathur.backup.ui.BackupApp

@Composable
fun Navigation(viewModel: BackupViewModel) {
    BackupApp(viewModel)
}

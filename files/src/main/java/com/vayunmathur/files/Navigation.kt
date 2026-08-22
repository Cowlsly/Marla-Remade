package com.vayunmathur.files

import androidx.compose.runtime.Composable
import com.vayunmathur.files.platform.FilesViewModel
import com.vayunmathur.files.ui.HomeDirectoryPage

@Composable
fun Navigation(viewModel: FilesViewModel) {
    HomeDirectoryPage(viewModel)
}

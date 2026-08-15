package com.vayunmathur.euicc.ui

import androidx.compose.runtime.Composable
import com.vayunmathur.euicc.platform.EuiccViewModel

@Composable
fun EuiccApp(viewModel: EuiccViewModel) {
    EuiccScreen(viewModel = viewModel)
}

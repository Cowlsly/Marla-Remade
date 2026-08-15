package com.vayunmathur.euicc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.euicc.platform.EuiccViewModel
import com.vayunmathur.library.ui.DynamicTheme

class MainActivity : ComponentActivity() {
    private val viewModel: EuiccViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DynamicTheme { Navigation(viewModel) } }
    }
}

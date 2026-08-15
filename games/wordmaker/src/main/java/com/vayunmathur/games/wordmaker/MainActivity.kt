package com.vayunmathur.games.wordmaker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.games.wordmaker.platform.WordMakerViewModel
import com.vayunmathur.library.ui.DynamicTheme

class MainActivity : ComponentActivity() {
    private val viewModel: WordMakerViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DynamicTheme { Navigation(viewModel) } }
    }
}
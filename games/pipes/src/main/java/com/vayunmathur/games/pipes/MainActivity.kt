package com.vayunmathur.games.pipes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.pipes.data.LevelPack
import com.vayunmathur.games.pipes.platform.PipesViewModel
import com.vayunmathur.games.pipes.ui.PipesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LevelPack.init(this)
        setContent {
            PipesTheme {
                val viewModel: PipesViewModel = viewModel()
                Navigation(viewModel)
            }
        }
    }
}

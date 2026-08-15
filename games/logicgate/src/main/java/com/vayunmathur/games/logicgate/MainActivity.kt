package com.vayunmathur.games.logicgate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.logicgate.platform.LogicViewModel
import com.vayunmathur.games.logicgate.ui.LogicGateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LogicGateTheme {
                val vm: LogicViewModel = viewModel()
                Navigation(vm)
            }
        }
    }
}

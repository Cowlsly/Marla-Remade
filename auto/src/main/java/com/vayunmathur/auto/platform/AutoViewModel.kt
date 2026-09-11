package com.vayunmathur.auto.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Holds what the status screen shows. The projection session will drive this. */
class AutoViewModel : ViewModel() {
    var state: AutoConnectionState by mutableStateOf(AutoConnectionState.Disconnected)
        private set
}

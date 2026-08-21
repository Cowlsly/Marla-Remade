package com.vayunmathur.cast.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.cast.tv.platform.MirrorActivity
import com.vayunmathur.cast.tv.platform.ReceiverController
import com.vayunmathur.cast.tv.platform.ReceiverPhase
import com.vayunmathur.cast.tv.service.ReceiverService
import com.vayunmathur.cast.tv.ui.ReceiverContent
import com.vayunmathur.library.ui.DynamicTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * The idle screen, and where the receiver is started from.
 *
 * Opening the app is what makes this TV castable: [ReceiverService] then keeps the sockets and the
 * mDNS registration alive whether or not this Activity is in front, which is why nothing here owns any
 * session state.
 *
 * No `Route.kt` or `Navigation.kt`: there is one screen, and a nav graph over a single destination
 * would be scaffolding around nothing.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReceiverService.start(this)
        setContent {
            DynamicTheme(darkTheme = true) {
                val state by ReceiverController.state.collectAsState()
                ReceiverContent(state)
            }
        }
        // The picture gets its own full-screen Activity, launched the moment frames start arriving.
        // distinctUntilChangedBy so a stream that reports its size again does not relaunch it.
        lifecycleScope.launch {
            ReceiverController.state
                .distinctUntilChangedBy { it.phase is ReceiverPhase.Mirroring }
                .collect { state ->
                    if (state.phase is ReceiverPhase.Mirroring) {
                        startActivity(Intent(this@MainActivity, MirrorActivity::class.java))
                    }
                }
        }
    }
}

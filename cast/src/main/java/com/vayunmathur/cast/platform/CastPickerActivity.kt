package com.vayunmathur.cast.platform

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.ui.CastPickerContent
import com.vayunmathur.library.ui.DynamicTheme

/**
 * The TV picker another app launches, through `CastPickerContract`.
 *
 * Exported and gated by the same signature permission as `ContentCastService`, and it exists for two
 * reasons that both come back to trust and to pairing:
 *
 *  - **Pairing happens once per TV for the whole device.** A self-contained SDK would make every app
 *    pair separately, six digits per app per TV, and would need `ACCESS_LOCAL_NETWORK` in every
 *    consumer's manifest. This screen is where that one pairing happens.
 *  - **`callingPackage` is only available to an Activity started for a result.** It is the identity the
 *    framework establishes rather than one the caller asserts, and resolving it through
 *    `PackageManager` is what lets the TV show "Receiving from YouPipe" without taking the sending
 *    app's word for it. A `Messenger` service has no equivalent, because `Message` dispatch goes
 *    through a `Handler` and the Binder identity is gone by the time the handler runs.
 *
 * Finishes `RESULT_OK` the moment a TV is connected and paired, which is the caller's cue to open a
 * session - and `RESULT_CANCELED` for everything else, including simply being backed out of.
 */
class CastPickerActivity : ComponentActivity() {

    private val viewModel: CastViewModel by viewModels { CastViewModelFactory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Backing out has to mean "cancelled" rather than an unset result, and the only way to
        // guarantee that is to set it before anything else can happen.
        setResult(RESULT_CANCELED)
        val appName = resolveCallerLabel()
        CastController.contentAppLabel = appName
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val state by viewModel.uiState.collectAsState()
                // The mDNS browse is tied to this screen being on screen: nothing else wants it, and
                // leaving it running afterwards is a radio kept awake for nothing.
                DisposableEffect(viewModel) {
                    viewModel.startScan()
                    onDispose { viewModel.stopScan() }
                }
                LaunchedEffect(state.connection) {
                    if (state.connection == CastConnection.Connected) {
                        setResult(RESULT_OK)
                        finish()
                    }
                }
                CastPickerContent(
                    state = state,
                    actions = PickerActions(viewModel, applicationContext),
                    appName = appName,
                    onCancel = { finish() },
                )
            }
        }
    }

    /**
     * The calling app's display name, or empty if it cannot be established.
     *
     * Empty is the same value screen mirroring uses, so the TV falls back to the phone's name - which
     * is the right answer for an unidentifiable caller, rather than inventing a label for it.
     */
    private fun resolveCallerLabel(): String {
        val caller = callingPackage ?: return ""
        return try {
            val manager = packageManager
            manager.getApplicationLabel(manager.getApplicationInfo(caller, 0)).toString()
        } catch (_: Exception) {
            ""
        }
    }
}

/**
 * The picker's half of [CastActions].
 *
 * The one difference from [CastViewModel]'s own behaviour, and the reason this exists: `connect` must
 * pass `thenMirror = false`. The ViewModel's version goes straight on to the screen-capture consent
 * dialog after pairing, which for an app that has its own content to send would be asking permission
 * to do the opposite of what it wanted.
 */
private class PickerActions(
    private val viewModel: CastViewModel,
    private val context: Context,
) : CastActions {

    override fun startScan() = viewModel.startScan()

    override fun stopScan() = viewModel.stopScan()

    override fun connect(device: CastDevice) =
        CastController.connect(context, device, thenMirror = false)

    override fun submitPairCode(code: String) = viewModel.submitPairCode(code)

    override fun disconnect() = viewModel.disconnect()

    override fun openLocalNetworkSettings() = viewModel.openLocalNetworkSettings()
}

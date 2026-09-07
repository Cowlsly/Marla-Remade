package com.vayunmathur.euicc.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.euicc.R
import com.vayunmathur.euicc.Route
import com.vayunmathur.library.ui.IconSim
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.SetupAction
import com.vayunmathur.library.ui.SetupScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack

/**
 * Manual activation-code entry, for when there is no QR code to point a camera at.
 *
 * The code is only sanity-checked, not parsed: an SGP.22 activation code is
 * `LPA:1$<smdp>$<matchingId>`, and the native core does the real parsing. Rejecting
 * anything more aggressively here risks turning a working code into an unexplained
 * refusal.
 */
@Composable
fun ActivationCodeScreen(backStack: NavBackStack<Route>) {
    var code by remember { mutableStateOf("") }
    val trimmed = code.trim()

    SetupScaffold(
        title = stringResource(R.string.activation_code_title),
        subtitle = stringResource(R.string.activation_code_text),
        icon = { IconSim() },
        backStack = backStack,
        primaryAction = SetupAction(
            label = stringResource(R.string.continue_button),
            onClick = { backStack.add(Route.Download(trimmed)) },
            enabled = looksLikeActivationCode(trimmed),
        ),
        secondaryAction = SetupAction(
            label = stringResource(R.string.activation_code_scan_button),
            onClick = { backStack.add(Route.ScanQr) },
        ),
        scrollBehavior = appBarScrollBehavior(),
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text(stringResource(R.string.activation_code_label)) },
            supportingText = { Text(stringResource(R.string.activation_code_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Whether [code] is worth handing to the native parser. Mirrors the scanner's check so a
 * typed code and a scanned one are held to the same standard.
 */
private fun looksLikeActivationCode(code: String): Boolean =
    code.startsWith("LPA:", ignoreCase = true) || code.contains('$')

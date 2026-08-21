package com.vayunmathur.musicbrainz.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.musicbrainz.R
import com.vayunmathur.musicbrainz.Route
import com.vayunmathur.musicbrainz.platform.MusicBrainzViewModel
import com.vayunmathur.musicbrainz.platform.TidalLoginStatus
import com.vayunmathur.musicbrainz.platform.TidalLoginUiState

/**
 * The Tidal device-code sign-in.
 *
 * Kicks off the flow on entry, shows the code and where to enter it, and pops itself once
 * the ViewModel reports the poll succeeded. Leaving before that cancels the poll.
 */
@Composable
fun TidalLoginPage(backStack: NavBackStack<Route>, viewModel: MusicBrainzViewModel) {
    val state by viewModel.tidalLogin.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.startTidalLogin() }

    DisposableEffect(Unit) {
        onDispose {
            // If the user backed out mid-flow, stop polling; a completed login already reset.
            if (viewModel.tidalLogin.value.status != TidalLoginStatus.Success) {
                viewModel.cancelTidalLogin()
            }
        }
    }

    LaunchedEffect(state.status) {
        if (state.status == TidalLoginStatus.Success) backStack.pop()
    }

    TidalLoginScreen(
        state = state,
        backStack = backStack,
        onRetry = viewModel::startTidalLogin,
    )
}

@Composable
fun TidalLoginScreen(
    state: TidalLoginUiState,
    backStack: NavBackStack<Route>,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    AppScaffold(title = stringResource(R.string.tidal_sign_in), backStack = backStack, scrollBehavior = appBarScrollBehavior()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            when (state.status) {
                TidalLoginStatus.Failed -> {
                    Text(
                        state.error ?: stringResource(R.string.tidal_login_failed),
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
                TidalLoginStatus.AwaitingUser -> {
                    Text(
                        stringResource(R.string.tidal_login_instructions),
                        textAlign = TextAlign.Center,
                    )
                    val code = state.userCode.orEmpty()
                    Text(
                        code,
                        style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(
                        onClick = { ExternalIntents.copyToClipboard(context, code) },
                    ) { Text(stringResource(R.string.tidal_copy_code)) }
                    state.verificationUri?.let { uri ->
                        Button(onClick = { ExternalIntents.openUrl(context, uri) }) {
                            Text(stringResource(R.string.tidal_open_browser))
                        }
                    }
                }
                else -> {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.tidal_login_starting))
                }
            }
        }
    }
}

package com.vayunmathur.setupwizard

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.setupwizard.platform.SetupFlow
import com.vayunmathur.setupwizard.platform.SetupIntents
import com.vayunmathur.setupwizard.platform.SetupViewModel
import com.vayunmathur.setupwizard.ui.DateTimeScreen
import com.vayunmathur.setupwizard.ui.FinishScreen
import com.vayunmathur.setupwizard.ui.GesturesScreen
import com.vayunmathur.setupwizard.ui.HandoffScreen
import com.vayunmathur.setupwizard.ui.LocationScreen
import com.vayunmathur.setupwizard.ui.MigrationScreen
import com.vayunmathur.setupwizard.ui.OemUnlockScreen
import com.vayunmathur.setupwizard.ui.WelcomeScreen

@Composable
fun Navigation(viewModel: SetupViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Welcome)
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state = viewModel.state

    /** Moves on from a step the user can come back to. */
    fun advance(from: Route) {
        SetupFlow.next(state.isPrimaryUser, from)?.let(backStack::add)
    }

    /**
     * Moves on from a step that is finished with: the entry is replaced rather than pushed, so
     * a step that handed off to another app and came back does not sit on the stack waiting to
     * hand off a second time when the user presses back.
     */
    fun advanceReplacing(from: Route) {
        SetupFlow.next(state.isPrimaryUser, from)?.let(backStack::setLast)
    }

    /** Opens something outside the wizard that the flow does not wait on. */
    fun open(intent: Intent) {
        try {
            (activity ?: context).startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w("SetupNavigation", "nothing handled ${intent.action}", e)
        }
    }

    // Setup is not a place the user can back out of: there is no other HOME activity while the
    // device is unprovisioned, so letting back finish this Activity leaves a black screen.
    BackHandler(enabled = backStack.backStack.size <= 1) {}

    MainNavigation(backStack) {
        entry<Route.Welcome> {
            LaunchedEffect(Unit) { viewModel.onEnterWelcome() }
            val canCall = context.packageManager
                .hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING)
            WelcomeScreen(
                state = state,
                languages = viewModel::availableLanguages,
                onLanguageSelected = viewModel::setLanguage,
                onAccessibility = { open(SetupIntents.accessibilitySettings()) },
                onEmergencyCall = if (canCall) {
                    { open(viewModel.system.emergencyDialerIntent()) }
                } else {
                    null
                },
                onNext = {
                    if (viewModel.welcomeLeadsToBootloaderWarning()) backStack.add(Route.OemUnlock)
                    else advance(Route.Welcome)
                },
            )
        }

        entry<Route.OemUnlock> {
            OemUnlockScreen(
                state = state,
                onStartAckTimer = viewModel::startBootloaderAckTimer,
                onRebootToBootloader = viewModel::rebootToBootloader,
                // Not a step of its own, so continuing resumes where the welcome step would
                // have gone rather than looking for whatever follows this screen.
                onContinue = { advanceReplacing(Route.Welcome) },
            )
        }

        entry<Route.Wifi> {
            val title = stringResource(R.string.connect_to_wi_fi)
            val description = stringResource(R.string.select_a_network)
            val skip = stringResource(R.string.set_up_without_wi_fi)
            HandoffScreen(
                intent = { SetupIntents.setupInternet(title, description, skip) },
                onCancelled = backStack::pop,
                onCompleted = { advanceReplacing(Route.Wifi) },
                onUnavailable = { advanceReplacing(Route.Wifi) },
            )
        }

        entry<Route.DateTime> {
            DateTimeScreen(
                state = state,
                timeZones = viewModel::timeZones,
                onTimeZoneSelected = viewModel::setTimeZone,
                onDateSelected = viewModel::setDate,
                onTimeSelected = viewModel::setTime,
                onClockTick = viewModel::refreshClock,
                onNext = { advance(Route.DateTime) },
            )
        }

        entry<Route.Location> {
            LocationScreen(
                state = state,
                onLocationEnabled = viewModel::setLocationEnabled,
                onWifiScanningEnabled = viewModel::setWifiScanningEnabled,
                onNext = { advance(Route.Location) },
            )
        }

        entry<Route.Security> {
            HandoffScreen(
                // A device that already has a lock screen has nothing to enrol, so the step
                // reports itself unavailable rather than opening an empty enrolment flow.
                intent = { if (state.deviceSecure) null else SetupIntents.biometricEnroll() },
                onCancelled = { viewModel.refreshSecurity(); backStack.pop() },
                onCompleted = { viewModel.refreshSecurity(); advanceReplacing(Route.Security) },
                onUnavailable = { advanceReplacing(Route.Security) },
            )
        }

        entry<Route.UpdaterSecurityPreview> {
            HandoffScreen(
                intent = { SetupIntents.securityPreview(context) },
                onCancelled = backStack::pop,
                onCompleted = { advanceReplacing(Route.UpdaterSecurityPreview) },
                onUnavailable = { advanceReplacing(Route.UpdaterSecurityPreview) },
            )
        }

        entry<Route.Migration> {
            // Resolved once rather than on every recomposition: this is a PackageManager query.
            val restore = remember { SetupIntents.restoreBackup(context) }
            // Nothing restores backups on this image yet, so the step is not shown at all
            // rather than offering a button that opens nothing. See SetupIntents.
            if (restore == null) {
                LaunchedEffect(Unit) { advanceReplacing(Route.Migration) }
            } else {
                val launcher = rememberLauncherForActivityResult(StartActivityForResult()) {
                    // Backing out of the restore leaves the user here, with Skip still
                    // available; anything else means a restore was started and the step is done.
                    if (it.resultCode != Activity.RESULT_CANCELED) advance(Route.Migration)
                }
                MigrationScreen(
                    onRestore = { launcher.launch(restore) },
                    onSkip = { advance(Route.Migration) },
                )
            }
        }

        entry<Route.Gestures> {
            val tutorial = remember { SetupIntents.gestureTutorial() }
            val launcher = rememberLauncherForActivityResult(StartActivityForResult()) {
                if (it.resultCode != Activity.RESULT_CANCELED) advance(Route.Gestures)
            }
            GesturesScreen(
                onTryIt = {
                    try {
                        launcher.launch(tutorial)
                    } catch (e: ActivityNotFoundException) {
                        Log.w("SetupNavigation", "no gesture tutorial on this image", e)
                        advance(Route.Gestures)
                    }
                },
                onSkip = { advance(Route.Gestures) },
            )
        }

        entry<Route.Finish> {
            FinishScreen(
                state = state,
                onFinish = { disableOemUnlocking ->
                    activity?.let { viewModel.finishSetup(it, disableOemUnlocking) }
                },
            )
        }
    }
}

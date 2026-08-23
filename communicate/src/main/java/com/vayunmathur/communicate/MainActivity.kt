package com.vayunmathur.communicate

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconSms
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.communicate.data.googlevoice.call.CallPhase
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallBridge
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallManager
import com.vayunmathur.communicate.telephony.GoogleVoiceSyncService
import com.vayunmathur.communicate.telephony.GoogleVoiceTelecom
import com.vayunmathur.communicate.ui.AccountsScreen
import com.vayunmathur.communicate.ui.CallLogsScreen
import com.vayunmathur.communicate.ui.ConversationScreen
import com.vayunmathur.communicate.ui.DialerScreen
import com.vayunmathur.communicate.ui.MessagesScreen
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallBridge
import com.vayunmathur.communicate.telephony.InAppCallTelecom
import com.vayunmathur.communicate.ui.call.InAppCallScreen
import com.vayunmathur.communicate.ui.googlevoice.GoogleVoiceSignInScreen
import com.vayunmathur.communicate.ui.whatsapp.WhatsAppRegistrationScreen
import com.vayunmathur.communicate.telephony.WhatsAppSyncService
import com.vayunmathur.communicate.data.whatsapp.WhatsAppLineSession
import com.vayunmathur.communicate.data.signal.SignalFeature
import com.vayunmathur.communicate.data.signal.SignalLineSession
import com.vayunmathur.communicate.telephony.SignalSyncService
import com.vayunmathur.communicate.ui.signal.SignalRegistrationScreen
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.Serializable

sealed interface Route : NavKey {
    @Serializable data object Main : Route
    @Serializable data object Accounts : Route
    @Serializable data object GoogleVoiceSignIn : Route
    @Serializable data object WhatsAppRegistration : Route
    @Serializable data object WhatsAppBackupImport : Route
    @Serializable data object SignalRegistration : Route

    @Serializable
    data class Conversation(
        val threadId: Long,
        val address: String,
        // Serialized as the enum name; defaults keep older back-stack entries valid.
        val line: CommunicateLine = CommunicateLine.Sim,
        val remoteId: String? = null,
        val subscriptionId: Int? = null,
        val isGroup: Boolean = false,
        val participants: List<String> = emptyList(),
        val groupTitle: String? = null,
    ) : Route
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
        }

        setContent {
            DynamicTheme {
                CommunicateApp()
            }
        }
    }

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 1001
    }
}

@Composable
private fun CommunicateApp() {
    val context = LocalContext.current
    val session = remember { GoogleVoiceSession.get(context) }
    val gvSignedIn by session.signedInFlow.collectAsState(initial = false)
    val waSession = remember { WhatsAppLineSession.get(context) }
    val waSignedIn by waSession.signedInFlow.collectAsState(initial = false)
    val sigSession = remember { SignalLineSession.get(context) }
    val sigSignedIn by sigSession.signedInFlow.collectAsState(initial = false)
    val inAppCallState by InAppCallRegistry.state.collectAsState()

    // Own the WhatsApp always-on receive state via its foreground sync service (dev-only).
    LaunchedEffect(waSignedIn) {
        if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return@LaunchedEffect
        if (waSignedIn) WhatsAppSyncService.start(context) else WhatsAppSyncService.stop(context)
    }

    // Own the Signal always-on receive state via its foreground sync service (dev-only).
    LaunchedEffect(sigSignedIn) {
        if (!SignalFeature.enabled) return@LaunchedEffect
        if (sigSignedIn) SignalSyncService.start(context) else SignalSyncService.stop(context)
    }

    // Keep the Telecom account fresh, but let the foreground service own always-on receive state.
    LaunchedEffect(gvSignedIn) {
        GoogleVoiceCallManager.init(context)
        GoogleVoiceCallManager.onIncomingCall = { from -> GoogleVoiceTelecom.addIncoming(context, from) }
        // Projects Google Voice call state onto the shared call screen.
        GoogleVoiceCallBridge.ensureStarted()
        if (gvSignedIn) {
            val number = session.phoneNumber() ?: context.getString(R.string.account_google_voice)
            GoogleVoiceTelecom.registerPhoneAccount(context, number)
            GoogleVoiceSyncService.start(context)
        } else {
            GoogleVoiceSyncService.stop(context)
        }
    }

    // WhatsApp and Signal calls share one Telecom account and one call screen. Registered whenever either
    // line is usable, so the system owns ringing and audio routing rather than the app approximating it.
    LaunchedEffect(waSignedIn, sigSignedIn) {
        val anyLine = (com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled && waSignedIn) ||
            (SignalFeature.enabled && sigSignedIn)
        if (anyLine) {
            InAppCallTelecom.registerPhoneAccount(context, context.getString(R.string.app_name))
            if (com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) {
                WhatsAppCallBridge.ensureStarted(context)
            }
        } else {
            InAppCallTelecom.unregisterPhoneAccount(context)
        }
    }

    val backStack = rememberNavBackStack<Route>(Route.Main)

    MainNavigation(backStack) {
        entry<Route.Main> {
            CommunicateTabs(backStack)
        }
        entry<Route.Accounts> {
            AccountsScreen(
                onBack = { backStack.pop() },
                onSignIn = { backStack.add(Route.GoogleVoiceSignIn) },
                onRegisterWhatsApp = {
                    if (com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) {
                        backStack.add(Route.WhatsAppRegistration)
                    }
                },
                onImportBackup = {
                    if (com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) {
                        backStack.add(Route.WhatsAppBackupImport)
                    }
                },
                onRegisterSignal = {
                    if (SignalFeature.enabled) {
                        backStack.add(Route.SignalRegistration)
                    }
                },
            )
        }
        entry<Route.GoogleVoiceSignIn> {
            GoogleVoiceSignInScreen(
                onBack = { backStack.pop() },
                onSignedIn = { backStack.pop() },
            )
        }
        entry<Route.WhatsAppRegistration> {
            WhatsAppRegistrationScreen(
                onBack = { backStack.pop() },
                onRegistered = { backStack.pop() },
            )
        }
        entry<Route.WhatsAppBackupImport> {
            com.vayunmathur.communicate.ui.whatsapp.WhatsAppBackupImportScreen(
                onBack = { backStack.pop() },
            )
        }
        entry<Route.SignalRegistration> {
            SignalRegistrationScreen(
                onBack = { backStack.pop() },
                onRegistered = { backStack.pop() },
            )
        }
        entry<Route.Conversation> { route ->
            ConversationScreen(
                threadId = route.threadId,
                address = route.address,
                line = route.line,
                remoteId = route.remoteId,
                subscriptionId = route.subscriptionId,
                isGroup = route.isGroup,
                participants = route.participants,
                groupTitle = route.groupTitle,
                onBack = { backStack.pop() },
            )
        }
    }

    // One call screen for every in-app line. Google Voice, WhatsApp and Signal all publish into
    // InAppCallRegistry, and the screen renders whatever the active line supports.
    if (inAppCallState.phase != InAppCallPhase.Idle) {
        InAppCallScreen(onClose = { InAppCallRegistry.clearEnded() })
    }
}

@Composable
private fun CommunicateTabs(backStack: NavBackStack<Route>) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val tabs = listOf(
        PagerTab(stringResource(R.string.nav_messages), { IconSms() }) {
            MessagesScreen(
                onOpenThread = { thread ->
                    backStack.add(
                        Route.Conversation(
                            threadId = thread.threadId,
                            address = thread.address,
                            line = thread.line,
                            remoteId = thread.remoteId,
                            subscriptionId = thread.subscriptionId,
                            isGroup = thread.isGroup,
                            participants = thread.participants,
                            groupTitle = thread.groupTitle,
                        ),
                    )
                },
                onOpenAccounts = { backStack.add(Route.Accounts) },
            )
        },
        PagerTab(stringResource(R.string.nav_dialer), { IconCall() }) { DialerScreen() },
        PagerTab(stringResource(R.string.nav_call_logs), { IconHistory() }) { CallLogsScreen() },
    )
    TabbedPagerScaffold(tabs = tabs, pagerState = pagerState, tabStyle = TabStyle.BottomNav)
}

package com.vayunmathur.email

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.email.data.DateMillisBackfill
import com.vayunmathur.email.data.EmailSyncWorker
import com.vayunmathur.email.data.ImapIdleService
import com.vayunmathur.email.data.OutboxSendWorker
import com.vayunmathur.email.platform.AppLifecycleTracker
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.email.platform.IntentState
import com.vayunmathur.email.ui.MainContent
import com.vayunmathur.email.widget.EmailWidgetReceiver
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.widgets.updateWidgetPreviews

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NetworkClient.init(this, TrustBundle.SYSTEM)
        updateWidgetPreviews(EmailWidgetReceiver::class)
        handleIntent(intent)
        EmailSyncWorker.scheduleHourlyNonInboxSync(this)
        OutboxSendWorker.runNow(this)
        ImapIdleService.start(this)
        DateMillisBackfill.runIfNeeded(lifecycleScope, this)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7331)
            }
        }
        enableEdgeToEdge()
        setContent {
            val viewModel: EmailViewModel = viewModel()
            DynamicTheme {
                MainContent(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppLifecycleTracker.isAppInForeground = true
        AppLifecycleTracker.tryStartIdleIfForeground(this)
    }

    override fun onResume() {
        super.onResume()
        AppLifecycleTracker.tryStartIdleIfForeground(this)
    }

    override fun onStop() {
        super.onStop()
        AppLifecycleTracker.isAppInForeground = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_VIEW) {
            val dataUri = intent.data
            if (dataUri != null) {
                val mime = intent.type
                val lastSeg = dataUri.lastPathSegment ?: ""
                val lowerMime = mime?.lowercase() ?: ""
                val isEmlMime = lowerMime.contains("rfc822") || lowerMime.contains("mbox")
                var isEmlExtension = lastSeg.endsWith(".eml", ignoreCase = true)
                if (!isEmlExtension && dataUri.scheme == "content") {
                    try {
                        contentResolver.query(dataUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) {
                                val name = c.getString(0) ?: ""
                                if (name.endsWith(".eml", ignoreCase = true)) isEmlExtension = true
                            }
                        }
                    } catch (_: Exception) { /* best-effort */ }
                }
                val looksLikeEml = isEmlMime || isEmlExtension
                if (looksLikeEml) {
                    IntentState.navigationRoute = Route.EmlViewer(dataUri.toString())
                    return
                }
            }
        }
        val accountEmail = intent.getStringExtra("accountEmail")
        val threadId = intent.getStringExtra("threadId")
        when {
            accountEmail != null && threadId != null ->
                IntentState.navigationRoute = Route.MessageThread(accountEmail, threadId)
            intent.getBooleanExtra("compose", false) ->
                IntentState.navigationRoute = Route.Composer()
            intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SENDTO -> {
                val to = if (intent.action == Intent.ACTION_SENDTO) intent.data?.schemeSpecificPart ?: "" else ""
                IntentState.navigationRoute = Route.Composer(
                    to = to,
                    subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "",
                    body = intent.getStringExtra(Intent.EXTRA_TEXT) ?: "",
                )
            }
        }
    }
}

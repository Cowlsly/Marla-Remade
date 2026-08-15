package com.vayunmathur.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.share.platform.SharePermissions
import com.vayunmathur.share.platform.ShareViewModel
import com.vayunmathur.share.platform.ShareViewModelFactory

class MainActivity : ComponentActivity() {
    private val shareViewModel: ShareViewModel by viewModels { ShareViewModelFactory(application) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingShareIntent(intent)
        setContent {
            DynamicTheme {
                PermissionsChecker(
                    permissions = SharePermissions.allSharePermissions(),
                    text = stringResource(R.string.share_permission_rationale),
                ) { Navigation(shareViewModel) }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }
    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> shareViewModel.handleShareIntent(intent)
        }
    }
}
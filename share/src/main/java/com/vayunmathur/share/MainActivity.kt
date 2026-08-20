package com.vayunmathur.share

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

/**
 * The app's own screen, reached from the launcher or by long-pressing the Quick Settings tile.
 *
 * Share-sheet intents do NOT land here: [SharePopupActivity] owns ACTION_SEND / SEND_MULTIPLE so
 * that picking Share from another app opens a bottom sheet instead of the whole app.
 */
class MainActivity : ComponentActivity() {
    private val shareViewModel: ShareViewModel by viewModels { ShareViewModelFactory(application) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                PermissionsChecker(
                    permissions = SharePermissions.allSharePermissions(),
                    text = stringResource(R.string.share_permission_rationale),
                ) { Navigation(shareViewModel) }
            }
        }
    }
}

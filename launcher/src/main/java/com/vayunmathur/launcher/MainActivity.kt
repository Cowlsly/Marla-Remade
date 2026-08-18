package com.vayunmathur.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.SideEffect
import com.vayunmathur.launcher.platform.ActivityBridge
import com.vayunmathur.launcher.platform.LauncherPrivilege
import com.vayunmathur.launcher.platform.LauncherViewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.rememberNavBackStack

/**
 * The home screen activity, and the [ActivityBridge] the Compose tree talks to.
 *
 * `singleTask` plus `stateNotNeeded` in the manifest are what make it behave like a home
 * app: HOME delivers a new intent to the existing instance rather than stacking another
 * copy, and the system may kill it without saving state because the workspace is in the
 * database, not in a `Bundle`.
 */
class MainActivity : ComponentActivity(), ActivityBridge {

    private val viewModel: LauncherViewModel by viewModels()
    private var backStack: NavBackStack<Route>? = null

    /** Asked before anything a stock device cannot do is offered. See [LauncherPrivilege]. */
    private val privilege get() = viewModel.privilege

    /**
     * The bind consent dialog is a plain intent, so it goes through the Activity Result API.
     *
     * The configure step cannot: `AppWidgetHost.startAppWidgetConfigureActivityForResult` starts
     * the activity itself via the legacy call, so its result can only arrive through
     * [onActivityResult]. One pending callback per flow is enough - both are modal to the user.
     */
    private var pendingBindResult: ((Boolean) -> Unit)? = null
    private var pendingConfigureResult: ((Boolean) -> Unit)? = null

    private val bindWidgetLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingBindResult
            pendingBindResult = null
            callback?.invoke(result.resultCode == Activity.RESULT_OK)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.bridge = this
        setContent {
            DynamicTheme {
                val stack = rememberNavBackStack<Route>(Route.Home)
                SideEffect { backStack = stack }
                Navigation(viewModel, this, stack)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStart()
        viewModel.refreshDefaultHome()
    }

    override fun onResume() {
        super.onResume()
        // Cheap enough to redo every time we come back: an app may have been installed or
        // uninstalled while we were away without us being alive to hear the callback.
        viewModel.reconcileNow()
    }

    override fun onStop() {
        viewModel.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        viewModel.bridge = null
        super.onDestroy()
    }

    /**
     * Pressing HOME from inside another app, or from a folder we left open, comes back here.
     * Resetting to the first page is what makes HOME feel like HOME rather than a no-op.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        backStack?.reset(Route.Home)
    }

    // ------------------------------------------------------------------
    // ActivityBridge
    // ------------------------------------------------------------------

    override fun requestBindWidget(
        appWidgetId: Int,
        provider: ComponentName,
        profileSerial: Long,
        onResult: (Boolean) -> Unit,
    ) {
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
            putExtra(
                AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE,
                viewModel.appsMonitor.userFor(profileSerial),
            )
        }
        pendingBindResult = onResult
        val started = runCatching { bindWidgetLauncher.launch(intent) }.isSuccess
        if (!started) {
            pendingBindResult = null
            // Reporting failure rather than leaving the flow hanging is what triggers the
            // caller's deleteAppWidgetId; a silent drop here leaks the allocated id.
            onResult(false)
        }
    }

    override fun startWidgetConfigure(appWidgetId: Int, onResult: (Boolean) -> Unit) {
        pendingConfigureResult = onResult
        val started = runCatching {
            viewModel.widgetHost.startAppWidgetConfigureActivityForResult(
                this,
                appWidgetId,
                0,
                REQUEST_CONFIGURE_WIDGET,
                null,
            )
        }.isSuccess
        if (!started) {
            pendingConfigureResult = null
            onResult(false)
        }
    }

    @Deprecated("AppWidgetHost only offers startActivityForResult for the configure step.")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONFIGURE_WIDGET) return
        val callback = pendingConfigureResult ?: return
        pendingConfigureResult = null
        callback(resultCode == Activity.RESULT_OK)
    }

    override fun pickWallpaper() {
        val chooser = Intent.createChooser(
            Intent(Intent.ACTION_SET_WALLPAPER),
            getString(R.string.app_name),
        )
        if (!startSafely(chooser)) AppMessages.show("No wallpaper picker available")
    }

    override fun requestHomeRole() {
        val roleManager = getSystemService(RoleManager::class.java)
        val intent = if (roleManager?.isRoleAvailable(RoleManager.ROLE_HOME) == true) {
            roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
        } else {
            // Without the role available there is no direct prompt, so the honest fallback is
            // the settings page where the choice actually lives.
            Intent(Settings.ACTION_HOME_SETTINGS)
        }
        if (!startSafely(intent)) AppMessages.show("Could not open home app settings")
    }

    override fun isDefaultHome(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == packageName
    }

    override fun requestUninstall(packageName: String) {
        // Silently when the ROM has granted DELETE_PACKAGES, which only a system build has; on an
        // ordinary device this is false and the user gets the system confirmation they expect.
        if (privilege.canUninstallSilently() && uninstallSilently(packageName)) return
        // ACTION_DELETE rather than the package installer session API: it needs no
        // permission and shows the system confirmation the user expects.
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))
        if (!startSafely(intent)) AppMessages.show("Could not open the uninstaller")
    }

    private fun uninstallSilently(packageName: String): Boolean = runCatching {
        val intent = Intent(this, MainActivity::class.java)
        val sender = PendingIntent.getActivity(
            this,
            REQUEST_SILENT_UNINSTALL,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        packageManager.packageInstaller.uninstall(packageName, sender.intentSender)
        true
    }.getOrDefault(false)

    override fun setWallpaperBlurRadius(radiusPx: Int) {
        // Off on some devices and in battery saver, where asking for a blur is silently ignored -
        // so the drawer's own translucent fill has to be enough on its own, and is.
        val manager = getSystemService(WindowManager::class.java)
        if (manager?.isCrossWindowBlurEnabled != true) return
        runCatching { window.setBackgroundBlurRadius(radiusPx) }
    }

    override fun launchAnimationOptions(left: Int, top: Int, right: Int, bottom: Int): Bundle? =
        runCatching {
            // Clip reveal from the icon's own rect, which is as close to the system launcher's app
            // open as an unprivileged app gets; the real shared-element handoff needs SystemUI.
            ActivityOptions
                .makeClipRevealAnimation(window.decorView, left, top, right - left, bottom - top)
                .toBundle()
        }.getOrNull()

    private fun startSafely(intent: Intent): Boolean = runCatching {
        startActivity(intent)
        true
    }.getOrDefault(false)

    private companion object {
        const val REQUEST_CONFIGURE_WIDGET = 1002
        const val REQUEST_SILENT_UNINSTALL = 1003
    }
}

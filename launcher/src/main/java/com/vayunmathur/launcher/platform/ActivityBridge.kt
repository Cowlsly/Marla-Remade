package com.vayunmathur.launcher.platform

import android.content.ComponentName
import android.os.Bundle
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The Activity-only capabilities the Compose tree needs.
 *
 * Binding a widget, running its configure activity and claiming the HOME role all need a
 * real Activity to start something for a result. There is no DI framework here, and
 * threading four callbacks down through the page tree would put Activity plumbing in every
 * signature — so `MainActivity` implements this and provides it through
 * [LocalActivityBridge].
 *
 * Deliberately narrow: only things that genuinely cannot be done from a `Context`.
 * Launching apps and reading shortcuts go through [LauncherAppsMonitor] instead.
 */
interface ActivityBridge {

    /**
     * Asks the user to allow binding [appWidgetId] to [provider].
     *
     * Only called after `bindAppWidgetIdIfAllowed` has already refused, which it does for
     * every third-party launcher — `BIND_APPWIDGET` is signature/privileged-only, so this
     * consent dialog is the only way in.
     */
    fun requestBindWidget(
        appWidgetId: Int,
        provider: ComponentName,
        profileSerial: Long,
        onResult: (Boolean) -> Unit,
    )

    /** Runs the provider's configure activity, if it declared one. */
    fun startWidgetConfigure(appWidgetId: Int, onResult: (Boolean) -> Unit)

    /** Opens the system wallpaper chooser. */
    fun pickWallpaper()

    /** Prompts to make this app the default home app. */
    fun requestHomeRole()

    /** Whether we currently hold the HOME role. Gates the features that require it. */
    fun isDefaultHome(): Boolean

    /** Opens the system uninstall confirmation for [packageName]. */
    fun requestUninstall(packageName: String)

    /**
     * Blurs whatever is behind this window, which on a home screen is the wallpaper.
     *
     * `Window.setBackgroundBlurRadius` is a window-level call, so nothing inside the Compose tree
     * can make it — and it is the only way to blur the wallpaper from here, since the drawer shares
     * this window rather than having one of its own. Degrades to nothing when the device has
     * cross-window blur disabled, which is a battery-saver setting as well as a hardware one.
     */
    fun setWallpaperBlurRadius(radiusPx: Int)

    /**
     * The launch animation options for an app opening out of the icon at these bounds, or null when
     * there is nothing to animate from.
     *
     * `ActivityOptions.makeClipRevealAnimation` needs a `View` to reveal *from*, which is the one
     * thing the Compose tree cannot hand over — a composable is not a View, and the only real one is
     * this Activity's decor view. Without this, every launch used null options and the app appeared
     * with the system's default cross-fade rather than growing out of the icon that was tapped.
     */
    fun launchAnimationOptions(left: Int, top: Int, right: Int, bottom: Int): Bundle?
}

val LocalActivityBridge = staticCompositionLocalOf<ActivityBridge> {
    error("No ActivityBridge provided - MainActivity must provide LocalActivityBridge")
}

package com.vayunmathur.launcher.domain

/** The second thing a drag can be dropped on, beside Remove. */
enum class DropBarSecondary { Uninstall, AppInfo }

/**
 * What the drop bar offers besides Remove.
 *
 * Launcher3 shows a *bar* of targets during a drag, not a single one, and the second target changes
 * with what is in the air: an app the user installed can be uninstalled from here, while a system
 * app cannot be, so it offers App info instead. A target that silently does nothing for half the
 * icons dropped on it is worse than no target, which is why this returns null rather than a
 * disabled Uninstall for the types that have nothing to offer.
 *
 * Pure, so the bar's contents are decided in one testable place rather than by a chain of `when`s
 * inside a composable.
 */
object DropBarTargets {

    /**
     * [canUninstall] is the same test the item menu applies: a user app in this profile.
     * Cross-profile uninstall is not permitted, and a system app cannot be removed at all.
     */
    fun secondaryFor(type: LauncherItemType, canUninstall: Boolean): DropBarSecondary? =
        when (type) {
            LauncherItemType.APPLICATION ->
                if (canUninstall) DropBarSecondary.Uninstall else DropBarSecondary.AppInfo
            // A pinned shortcut belongs to an app that is staying, so App info is the honest
            // second target: uninstalling from here would remove far more than was dragged.
            LauncherItemType.DEEP_SHORTCUT -> DropBarSecondary.AppInfo
            // A folder is not one app, and a widget is not an app at all.
            LauncherItemType.FOLDER, LauncherItemType.APPWIDGET -> null
        }
}

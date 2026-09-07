package com.vayunmathur.logviewer.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale

/**
 * The lines that sit above every log and every error report.
 *
 * The point of the header is that a report pasted into an issue answers the obvious first questions
 * without anyone having to ask them: which build, which app version, whether the device is in a
 * state that explains the problem by itself.
 */
internal object ReportHeaders {

    private const val TAG = "ReportHeaders"

    /**
     * Device-wide context: the user type, and any flags that change how much the rest can be
     * trusted.
     *
     * The user type is only printed for a secondary user or profile, because the system user is the
     * uninteresting default. A missing value is omitted rather than guessed - see
     * [HiddenFrameworkApi].
     */
    fun addDeviceLines(context: Context, dst: MutableList<String>) {
        val userManager = context.getSystemService(UserManager::class.java)
        if (userManager != null && !userManager.isSystemUser) {
            val userType = HiddenFrameworkApi.userType(context)
            if (userType != null) {
                dst += "userType: " + userType.removePrefix("android.os.usertype.").lowercase(Locale.US)
            }
        }

        val flags = mutableListOf<String>()
        if (HiddenFrameworkApi.isBootloaderUnlocked(context) == true) {
            flags += "bootloader unlocked"
        }
        val developmentSettings = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0,
        )
        if (developmentSettings != 0) {
            flags += "dev options enabled"
        }
        if (flags.isNotEmpty()) {
            dst += "flags: " + flags.joinToString(", ")
        }
    }

    /**
     * Which build of which app the report is about.
     *
     * `sharedUid` is only printed when the installed package is the same APK as the one the report
     * came from: a shared uid is a property of the installed package, and printing the installed
     * one next to a different, older `ApplicationInfo` would be actively misleading.
     */
    fun addPackageLines(context: Context, appInfo: ApplicationInfo, dst: MutableList<String>) {
        val packageInfo = try {
            context.packageManager.getPackageInfo(appInfo.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        // ApplicationInfo.longVersionCode is hidden. PackageInfo's is public but describes what is
        // installed now, which for a crash report may already be a newer version - so it is the
        // fallback, not the first choice.
        val versionCode = HiddenFrameworkApi.longField(appInfo, "longVersionCode")
            ?: packageInfo?.longVersionCode
            ?: 0L
        dst += "package: ${appInfo.packageName}:$versionCode, targetSdk ${appInfo.targetSdkVersion}"

        if (packageInfo == null) return
        val installedAppInfo = packageInfo.applicationInfo ?: return
        // ApplicationInfo.getBaseCodePath() is hidden and returns exactly this field.
        if (appInfo.sourceDir == installedAppInfo.sourceDir) {
            @Suppress("DEPRECATION")
            val sharedUserId = packageInfo.sharedUserId
            if (sharedUserId != null) dst += "sharedUid: $sharedUserId"
        }
    }

    /** The app's display name, falling back to the package name when it cannot be resolved. */
    fun loadAppLabel(context: Context, packageName: String): CharSequence {
        val packageManager = context.packageManager
        return try {
            packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager)
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    /** Who installed the app, which is often the whole explanation for a crash. */
    fun installingPackage(context: Context, packageName: String): String? = try {
        context.packageManager.getInstallSourceInfo(packageName).installingPackageName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun readFileAsString(path: String): String? = try {
        String(Files.readAllBytes(Paths.get(path)), Charsets.UTF_8)
    } catch (e: IOException) {
        Log.e(TAG, "unable to read $path", e)
        null
    }
}

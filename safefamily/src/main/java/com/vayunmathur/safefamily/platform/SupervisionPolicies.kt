package com.vayunmathur.safefamily.platform

import android.app.supervision.PackageUsagePolicy
import android.app.supervision.SupervisionManager
import android.content.Context
import android.util.Log
import androidx.core.content.getSystemService
import java.time.Duration

private const val TAG = "SafeFamilyPolicy"

/**
 * Writes enforcement decisions to the platform.
 *
 * `SupervisionManager.setPolicy` is the only policy channel that exists - `validatePolicyLocked`
 * rejects anything that is not a [PackageUsagePolicy] - and it needs `MANAGE_SUPERVISION`, which
 * arrives with the `SYSTEM_SUPERVISION` role rather than with privileged placement. On a build
 * where the role is unheld every call here fails, which is why each one is guarded rather than
 * allowed to throw into a receiver.
 *
 * ## Why the timing is ours and not the platform's
 *
 * `TYPE_TIME_LIMIT` sounds like it should do the work, but
 * `SupervisionService.applyPackageUsagePolicy` suspends the package the moment the policy is
 * written - there is a standing `TODO(b/482425646): Only suspend the package when limit is
 * reached`, and no usage tracking, no timer and no daily reset behind it. The stored `Duration`
 * is never read by the platform.
 *
 * So [block] and [allow] are what actually gate an app, and the decision of *when* to call them
 * comes from `UsageStatsManager` observers ([AppLimits]) and from the bedtime alarm
 * ([BedtimeScheduler]). [limit] exists to record the parent's intent in the platform's own store
 * so it survives us and shows up in `getPolicies`, not because it enforces anything.
 */
class SupervisionPolicies(context: Context) {

    private val manager = context.getSystemService<SupervisionManager>()

    /** False when the role is unheld or the platform service is missing; nothing will enforce. */
    val isAvailable: Boolean get() = manager != null

    fun block(packageName: String) = write(packageName, PackageUsagePolicy.TYPE_BLOCKED)

    fun allow(packageName: String) = write(packageName, PackageUsagePolicy.TYPE_ALLOWED)

    /**
     * Records the parent's daily cap in the platform policy store.
     *
     * Enforcement does not follow from this; see the class note. Rejected by the platform above
     * 24 h, on a negative duration, and entirely unless the
     * `enable_supervision_package_usage_apis` aconfig flag is on - which MAOS enables from
     * `build/release/aconfig/cp2a/`.
     */
    fun limit(packageName: String, minutes: Int) {
        val policy = runCatching {
            PackageUsagePolicy.Builder(packageName, PackageUsagePolicy.TYPE_TIME_LIMIT)
                .setTimeLimit(Duration.ofMinutes(minutes.toLong()))
                .build()
        }.getOrElse {
            // IllegalStateException from performBuild when the flag is off or the duration is
            // out of range. Not fatal: the cap still works, because we time it ourselves.
            Log.w(TAG, "could not build a time-limit policy for $packageName", it)
            return
        }
        set(policy)
    }

    private fun write(packageName: String, type: Int) {
        val policy = runCatching {
            PackageUsagePolicy.Builder(packageName, type).build()
        }.getOrElse {
            Log.w(TAG, "could not build a type-$type policy for $packageName", it)
            return
        }
        set(policy)
    }

    private fun set(policy: PackageUsagePolicy) {
        val manager = manager ?: run {
            Log.w(TAG, "no SupervisionManager; is the supervision role held?")
            return
        }
        runCatching { manager.setPolicy(policy) }
            .onFailure { Log.w(TAG, "setPolicy rejected for ${policy.packageName}", it) }
    }
}

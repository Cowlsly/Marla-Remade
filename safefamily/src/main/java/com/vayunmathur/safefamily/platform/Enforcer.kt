package com.vayunmathur.safefamily.platform

import android.content.Context
import android.util.Log
import com.vayunmathur.safefamily.data.AppRule
import com.vayunmathur.safefamily.data.SupervisionRules
import java.time.LocalDate
import java.time.LocalDateTime

private const val TAG = "SafeFamilyEnforcer"

/**
 * Decides what each supervised app's state should be, and makes it so.
 *
 * Everything that can change an outcome funnels through [reconcile]: the bedtime alarm, a usage
 * observer firing, a rule edit, and boot. Recomputing the whole picture each time is what keeps
 * the four of them from disagreeing - an incremental "block this one app" path would have to
 * know whether bedtime was also active, and would get it wrong at exactly the boundary where it
 * matters.
 *
 * ## Precedence
 *
 * Bedtime wins over a daily limit. Both are reasons to block and neither is a reason to unblock
 * while the other holds, so the state is simply the OR of them; the ordering only matters for
 * the *reason* shown to the user.
 *
 * ## Why a limit trip is remembered
 *
 * The platform observer fires once when the budget runs out and does not fire again. If we
 * treated "blocked" as derived purely from live usage, the next [reconcile] - a bedtime
 * boundary, say - would unblock the app because nothing in the current state says the limit was
 * reached. [LimitState] records the trip for the rest of the local day, and midnight clears it.
 */
class Enforcer(private val context: Context) {

    private val rules = SupervisionRules.get(context)
    private val policies = SupervisionPolicies(context)
    private val limits = AppLimits(context)
    private val bedtime = BedtimeScheduler(context)
    private val limitState = LimitState(context)

    /** Note that [packageName] has used up its daily budget, then reconcile. */
    suspend fun onLimitReached(packageName: String) {
        limitState.markReached(packageName)
        reconcile()
    }

    /**
     * Bring the device in line with the stored rules.
     *
     * Safe to call redundantly - writing a policy that already matches is a no-op to the user,
     * and the platform de-duplicates the resulting hidden-state change.
     */
    suspend fun reconcile() {
        if (!policies.isAvailable) {
            Log.w(TAG, "no supervision policy channel; nothing will be enforced")
            return
        }
        val schedule = rules.scheduleNow()
        val all = rules.allRulesNow()
        val nightNow = schedule.activeAt(LocalDateTime.now())
        limitState.pruneToToday()
        // Cheap and idempotent, and it has to happen before AppLimits.sync below: without the
        // usage-stats op an observer is armed with timeUsed = 0.
        UsageAccess.ensure(context)

        for (rule in all) {
            val blocked = shouldBlock(rule, nightNow)
            if (blocked) policies.block(rule.packageName) else policies.allow(rule.packageName)
        }

        // Record the parent's caps in the platform's own policy store. Does not enforce - see
        // SupervisionPolicies.limit - but makes the intent visible to getPolicies and to anything
        // that inspects supervision state later.
        for (rule in all) {
            rule.dailyLimitMinutes?.let { policies.limit(rule.packageName, it) }
        }

        limits.sync(all.filter { !shouldBlock(it, nightNow) })
        bedtime.arm(schedule)
    }

    private fun shouldBlock(rule: AppRule, nightNow: Boolean): Boolean =
        (rule.blockedAtBedtime && nightNow) || limitState.hasReached(rule.packageName)
}

/**
 * Which apps have used up today's budget.
 *
 * Deliberately not in Room: it is per-day scratch state that must be readable from a broadcast
 * receiver on the main thread before any coroutine is available, and it is worthless after
 * midnight. SharedPreferences is the right weight for that.
 */
class LimitState(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markReached(packageName: String) {
        pruneToToday()
        val next = reached() + packageName
        prefs.edit().putStringSet(KEY_REACHED, next).putString(KEY_DAY, today()).apply()
    }

    fun hasReached(packageName: String): Boolean = packageName in reached()

    /** Drop yesterday's trips. This is the daily reset; there is no other. */
    fun pruneToToday() {
        if (prefs.getString(KEY_DAY, null) == today()) return
        prefs.edit().remove(KEY_REACHED).putString(KEY_DAY, today()).apply()
    }

    private fun reached(): Set<String> = prefs.getStringSet(KEY_REACHED, emptySet()) ?: emptySet()

    private fun today(): String = LocalDate.now().toString()

    private companion object {
        const val PREFS = "safefamily_limits"
        const val KEY_REACHED = "reached"
        const val KEY_DAY = "day"
    }
}

package com.vayunmathur.safefamily.platform

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.safefamily.data.AppRule
import com.vayunmathur.safefamily.data.BedtimeSchedule
import com.vayunmathur.safefamily.data.SupervisionRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One launchable app the parent can put a rule on. */
data class SupervisableApp(
    val packageName: String,
    val label: String,
    val rule: AppRule?,
) {
    val isSupervised: Boolean get() = rule != null
}

/** Everything the bedtime and app-limit screens draw. */
data class SupervisionUiState(
    val schedule: BedtimeSchedule = BedtimeSchedule(),
    val apps: List<SupervisableApp> = emptyList(),
    val loading: Boolean = true,
)

class SupervisionViewModel(app: Application) : AndroidViewModel(app) {

    private val rules = SupervisionRules.get(app)
    private val installed = MutableStateFlow<List<Pair<String, String>>>(emptyList())

    val state: StateFlow<SupervisionUiState> = combine(
        rules.schedule,
        rules.allRules,
        installed,
    ) { schedule, ruleList, apps ->
        val byPackage = ruleList.associateBy { it.packageName }
        SupervisionUiState(
            schedule = schedule,
            apps = apps.map { (pkg, label) -> SupervisableApp(pkg, label, byPackage[pkg]) },
            loading = apps.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SupervisionUiState())

    init {
        viewModelScope.launch { installed.value = loadLaunchableApps() }
    }

    fun setScheduleEnabled(enabled: Boolean) = edit { it.copy(enabled = enabled) }

    fun setStart(hour: Int, minute: Int) = edit { it.copy(startMinute = hour * 60 + minute) }

    fun setEnd(hour: Int, minute: Int) = edit { it.copy(endMinute = hour * 60 + minute) }

    fun toggleDay(dayIndex: Int) = edit {
        it.copy(daysMask = it.daysMask xor (1 shl dayIndex))
    }

    fun setBedtimeBlocked(packageName: String, blocked: Boolean) = editRule(packageName) {
        it.copy(blockedAtBedtime = blocked)
    }

    fun setDailyLimit(packageName: String, minutes: Int?) = editRule(packageName) {
        it.copy(dailyLimitMinutes = minutes)
    }

    private fun edit(transform: (BedtimeSchedule) -> BedtimeSchedule) {
        viewModelScope.launch {
            rules.setSchedule(transform(rules.scheduleNow()))
            reconcile()
        }
    }

    /**
     * Applies [transform] to the app's rule, creating one if absent and deleting it once it
     * carries no restriction. A row that restricts nothing is indistinguishable from no row, and
     * keeping it would make the app look supervised in the list when it is not.
     */
    private fun editRule(packageName: String, transform: (AppRule) -> AppRule) {
        viewModelScope.launch {
            val existing = rules.rule(packageName) ?: AppRule(packageName)
            val next = transform(existing)
            if (next.dailyLimitMinutes == null && !next.blockedAtBedtime) {
                rules.delete(next)
            } else {
                rules.upsert(next)
            }
            reconcile()
        }
    }

    private suspend fun reconcile() = withContext(Dispatchers.IO) {
        Enforcer(getApplication()).reconcile()
    }

    /**
     * Launchable apps, excluding ourselves.
     *
     * Supervising the supervision app would let a limit lock the parent out of the controls, so
     * it is not offered. Queried with `MATCH_ALL` against the launcher intent rather than listing
     * every installed package, because a package with no launcher entry is not something a child
     * "uses" and would only make the list unusable.
     */
    private suspend fun loadLaunchableApps(): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val self = getApplication<Application>().packageName
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                .mapNotNull { it.activityInfo?.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != self }
                .map { it.packageName to it.label(pm) }
                .sortedBy { it.second.lowercase() }
        }

    private fun ApplicationInfo.label(pm: PackageManager): String =
        runCatching { pm.getApplicationLabel(this).toString() }.getOrDefault(packageName)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

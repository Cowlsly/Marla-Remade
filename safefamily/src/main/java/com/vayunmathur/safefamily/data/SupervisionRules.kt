package com.vayunmathur.safefamily.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The single owner of [SafeFamilyDatabase], shared by the UI, the alarm receiver, the usage
 * observers and the bound [android.app.supervision.SupervisionAppService].
 *
 * Those last three are the reason this is a process-wide singleton rather than a ViewModel
 * dependency: enforcement runs with no Activity alive.
 */
class SupervisionRules private constructor(context: Context) :
    RoomRepository<SafeFamilyDatabase>(context, SafeFamilyDatabase::class, DB_NAME) {

    private val rules get() = db.appRuleDao()
    private val bedtime get() = db.bedtimeDao()

    val allRules: Flow<List<AppRule>> = rules.allFlow()

    /** Apps with a daily cap, which is the set the usage observers are registered for. */
    val limitedRules: Flow<List<AppRule>> =
        rules.allFlow().map { list -> list.filter { it.dailyLimitMinutes != null } }

    suspend fun allRulesNow(): List<AppRule> = rules.all()

    suspend fun rule(packageName: String): AppRule? = rules.byPackage(packageName)

    suspend fun upsert(rule: AppRule) = rules.upsert(rule)

    suspend fun delete(rule: AppRule) = rules.delete(rule)

    /**
     * The schedule, defaulted rather than nullable.
     *
     * A missing row and a disabled schedule mean the same thing to every caller, so collapsing
     * them here keeps that distinction out of the enforcer and the UI.
     */
    val schedule: Flow<BedtimeSchedule> =
        bedtime.scheduleFlow().map { it ?: BedtimeSchedule() }

    suspend fun scheduleNow(): BedtimeSchedule = bedtime.schedule() ?: BedtimeSchedule()

    suspend fun setSchedule(schedule: BedtimeSchedule) = bedtime.upsert(schedule)

    companion object {
        @Volatile private var instance: SupervisionRules? = null

        fun get(context: Context): SupervisionRules =
            instance ?: synchronized(this) {
                instance ?: SupervisionRules(context).also { instance = it }
            }
    }
}

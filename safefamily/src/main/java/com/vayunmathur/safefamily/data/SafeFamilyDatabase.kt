package com.vayunmathur.safefamily.data

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

const val DB_NAME = "safefamily-db"

@Dao
interface AppRuleDao {
    @Query("SELECT * FROM AppRule ORDER BY packageName")
    fun allFlow(): Flow<List<AppRule>>

    @Query("SELECT * FROM AppRule")
    suspend fun all(): List<AppRule>

    @Query("SELECT * FROM AppRule WHERE packageName = :packageName")
    suspend fun byPackage(packageName: String): AppRule?

    @Upsert
    suspend fun upsert(rule: AppRule)

    @Delete
    suspend fun delete(rule: AppRule)
}

@Dao
interface BedtimeDao {
    @Query("SELECT * FROM BedtimeSchedule WHERE id = :id")
    fun scheduleFlow(id: Int = BedtimeSchedule.SINGLETON_ID): Flow<BedtimeSchedule?>

    @Query("SELECT * FROM BedtimeSchedule WHERE id = :id")
    suspend fun schedule(id: Int = BedtimeSchedule.SINGLETON_ID): BedtimeSchedule?

    @Upsert
    suspend fun upsert(schedule: BedtimeSchedule)
}

@Database(entities = [AppRule::class, BedtimeSchedule::class], version = 1, exportSchema = false)
abstract class SafeFamilyDatabase : RoomDatabase() {
    abstract fun appRuleDao(): AppRuleDao
    abstract fun bedtimeDao(): BedtimeDao
}

package com.vayunmathur.education.data

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import com.vayunmathur.library.util.DatabaseMigrations
import kotlinx.coroutines.flow.Flow

const val DB_NAME = "education-db"

@Dao
interface LearnerDao {
    @Query("SELECT * FROM Learner WHERE id = ${Learner.SINGLE_ID}")
    fun getFlow(): Flow<Learner?>

    @Query("SELECT * FROM Learner WHERE id = ${Learner.SINGLE_ID}")
    suspend fun get(): Learner?

    @Upsert
    suspend fun upsert(value: Learner)
}

@Dao
interface SkillProgressDao {
    @Query("SELECT * FROM SkillProgress")
    fun getAllFlow(): Flow<List<SkillProgress>>

    @Query("SELECT * FROM SkillProgress")
    suspend fun getAll(): List<SkillProgress>

    @Query("SELECT * FROM SkillProgress WHERE skillId = :skillId")
    suspend fun get(skillId: String): SkillProgress?

    @Upsert
    suspend fun upsert(value: SkillProgress)
}

@Dao
interface DeadlineDao {
    @Query("SELECT * FROM Deadline ORDER BY dueEpochDay ASC")
    fun getAllFlow(): Flow<List<Deadline>>

    @Query("SELECT * FROM Deadline WHERE moduleType = :moduleType AND moduleId = :moduleId LIMIT 1")
    suspend fun getFor(moduleType: String, moduleId: String): Deadline?

    @Upsert
    suspend fun upsert(value: Deadline)

    @Delete
    suspend fun delete(value: Deadline)
}

@Database(
    entities = [Learner::class, SkillProgress::class, Deadline::class],
    version = 1,
    exportSchema = false,
)
abstract class EducationDatabase : RoomDatabase() {
    abstract fun learnerDao(): LearnerDao
    abstract fun skillProgressDao(): SkillProgressDao
    abstract fun deadlineDao(): DeadlineDao

    companion object : DatabaseMigrations {
        override val migrations = emptyList<androidx.room3.migration.Migration>()
    }
}

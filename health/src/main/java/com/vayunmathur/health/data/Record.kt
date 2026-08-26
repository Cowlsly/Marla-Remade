package com.vayunmathur.health.data
import androidx.room3.ColumnTypeConverter
import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Update
import androidx.room3.Delete
import androidx.room3.migration.Migration
import androidx.sqlite.execSQL
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.Flow
import java.time.Instant

enum class RecordType {
    Steps, Wheelchair, Distance, CaloriesTotal, CaloriesActive, CaloriesBasal, Floors, Elevation,
    HeartRate, RestingHeartRate, HeartRateVariabilityRmssd, RespiratoryRate, OxygenSaturation,
    BloodPressure, BloodGlucose, Vo2Max, SkinTemperature,
    Weight, Height, BodyFat, LeanBodyMass, BoneMass, BodyWaterMass,
    Sleep, Mindfulness, Hydration, Nutrition, Exercise
}

@Serializable
data class NutritionData(
    val protein: Double = 0.0,
    val carbohydrates: Double = 0.0,
    val fat: Double = 0.0,
    val fiber: Double = 0.0,
    val sugar: Double = 0.0,
    val sodium: Double = 0.0,
    val biotin: Double = 0.0,
    val caffeine: Double = 0.0,
    val calcium: Double = 0.0,
    val chloride: Double = 0.0,
    val cholesterol: Double = 0.0,
    val chromium: Double = 0.0,
    val copper: Double = 0.0,
    val folate: Double = 0.0,
    val folicAcid: Double = 0.0,
    val iodine: Double = 0.0,
    val iron: Double = 0.0,
    val magnesium: Double = 0.0,
    val manganese: Double = 0.0,
    val molybdenum: Double = 0.0,
    val monounsaturatedFat: Double = 0.0,
    val niacin: Double = 0.0,
    val pantothenicAcid: Double = 0.0,
    val phosphorus: Double = 0.0,
    val polyunsaturatedFat: Double = 0.0,
    val potassium: Double = 0.0,
    val riboflavin: Double = 0.0,
    val saturatedFat: Double = 0.0,
    val selenium: Double = 0.0,
    val thiamin: Double = 0.0,
    val transFat: Double = 0.0,
    val unsaturatedFat: Double = 0.0,
    val vitaminA: Double = 0.0,
    val vitaminB12: Double = 0.0,
    val vitaminB6: Double = 0.0,
    val vitaminC: Double = 0.0,
    val vitaminD: Double = 0.0,
    val vitaminE: Double = 0.0,
    val vitaminK: Double = 0.0,
    val zinc: Double = 0.0,
    val calories: Double = 0.0
)

@Serializable
data class SleepStage(
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val stage: Int
)

@Serializable
data class SleepData(
    val awakeDurationMillis: Long = 0,
    val remDurationMillis: Long = 0,
    val lightDurationMillis: Long = 0,
    val deepDurationMillis: Long = 0,
    val unknownDurationMillis: Long = 0,
    val stagesJson: String? = null
)

@Serializable
data class ExerciseSegmentData(
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val segmentType: Int,
    val repetitions: Int,
)

@Serializable
data class ExerciseLapData(
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val lengthMeters: Double?,
)

@Serializable
data class ExerciseData(
    val exerciseType: Int = 0,
    val title: String? = null,
    val notes: String? = null,
    val segmentsJson: String? = null,
    val lapsJson: String? = null,
    val hasRoute: Boolean = false,
)

@Entity(indices = [Index(value = ["type", "startTime", "endTime"])])
data class Record(
    val id: String,
    val index : Int, // for multisample records
    val type: RecordType,
    val startTime: Instant,
    val endTime: Instant,
    val value: Double,
    val secondaryValue: Double = 0.0,
    @Embedded(prefix = "nutrition_") val nutritionData: NutritionData? = null,
    @Embedded(prefix = "sleep_") val sleepData: SleepData? = null,
    @Embedded(prefix = "exercise_") val exerciseData: ExerciseData? = null,
    val metadata: String? = null,
    @PrimaryKey val primaryKey: String = "$id-$index",
)

@Dao
interface HealthDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(records: List<Record>)

    @Query("DELETE FROM Record WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT * FROM Record WHERE type = :type ORDER BY startTime DESC")
    fun getRecordsFlow(type: RecordType): Flow<List<Record>>

    @Query("SELECT * FROM Record WHERE type = :type ORDER BY startTime DESC LIMIT 1")
    suspend fun getLastRecord(type: RecordType): Record?

    @Query("SELECT COALESCE(SUM(CASE WHEN :type = 'Nutrition' THEN nutrition_calories ELSE value END), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    /**
     * Single query that returns the summed nutrient totals over the range as an [NutritionData].
     * Replaces the previous ~38 per-nutrient `sumXxxInRange` queries; callers read individual
     * nutrients off the returned object (see `nutrientCatalog`).
     */
    @Query("""
        SELECT
            COALESCE(SUM(nutrition_protein), 0.0) AS protein,
            COALESCE(SUM(nutrition_carbohydrates), 0.0) AS carbohydrates,
            COALESCE(SUM(nutrition_fat), 0.0) AS fat,
            COALESCE(SUM(nutrition_fiber), 0.0) AS fiber,
            COALESCE(SUM(nutrition_sugar), 0.0) AS sugar,
            COALESCE(SUM(nutrition_sodium), 0.0) AS sodium,
            COALESCE(SUM(nutrition_biotin), 0.0) AS biotin,
            COALESCE(SUM(nutrition_caffeine), 0.0) AS caffeine,
            COALESCE(SUM(nutrition_calcium), 0.0) AS calcium,
            COALESCE(SUM(nutrition_chloride), 0.0) AS chloride,
            COALESCE(SUM(nutrition_cholesterol), 0.0) AS cholesterol,
            COALESCE(SUM(nutrition_chromium), 0.0) AS chromium,
            COALESCE(SUM(nutrition_copper), 0.0) AS copper,
            COALESCE(SUM(nutrition_folate), 0.0) AS folate,
            COALESCE(SUM(nutrition_folicAcid), 0.0) AS folicAcid,
            COALESCE(SUM(nutrition_iodine), 0.0) AS iodine,
            COALESCE(SUM(nutrition_iron), 0.0) AS iron,
            COALESCE(SUM(nutrition_magnesium), 0.0) AS magnesium,
            COALESCE(SUM(nutrition_manganese), 0.0) AS manganese,
            COALESCE(SUM(nutrition_molybdenum), 0.0) AS molybdenum,
            COALESCE(SUM(nutrition_monounsaturatedFat), 0.0) AS monounsaturatedFat,
            COALESCE(SUM(nutrition_niacin), 0.0) AS niacin,
            COALESCE(SUM(nutrition_pantothenicAcid), 0.0) AS pantothenicAcid,
            COALESCE(SUM(nutrition_phosphorus), 0.0) AS phosphorus,
            COALESCE(SUM(nutrition_polyunsaturatedFat), 0.0) AS polyunsaturatedFat,
            COALESCE(SUM(nutrition_potassium), 0.0) AS potassium,
            COALESCE(SUM(nutrition_riboflavin), 0.0) AS riboflavin,
            COALESCE(SUM(nutrition_saturatedFat), 0.0) AS saturatedFat,
            COALESCE(SUM(nutrition_selenium), 0.0) AS selenium,
            COALESCE(SUM(nutrition_thiamin), 0.0) AS thiamin,
            COALESCE(SUM(nutrition_transFat), 0.0) AS transFat,
            COALESCE(SUM(nutrition_unsaturatedFat), 0.0) AS unsaturatedFat,
            COALESCE(SUM(nutrition_vitaminA), 0.0) AS vitaminA,
            COALESCE(SUM(nutrition_vitaminB12), 0.0) AS vitaminB12,
            COALESCE(SUM(nutrition_vitaminB6), 0.0) AS vitaminB6,
            COALESCE(SUM(nutrition_vitaminC), 0.0) AS vitaminC,
            COALESCE(SUM(nutrition_vitaminD), 0.0) AS vitaminD,
            COALESCE(SUM(nutrition_vitaminE), 0.0) AS vitaminE,
            COALESCE(SUM(nutrition_vitaminK), 0.0) AS vitaminK,
            COALESCE(SUM(nutrition_zinc), 0.0) AS zinc,
            COALESCE(SUM(nutrition_calories), 0.0) AS calories
        FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime
    """)
    fun sumNutritionInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<NutritionData>

    @Query("SELECT MIN(value) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun minInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double?>
    @Query("SELECT MAX(value) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun maxInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double?>
    @Query("SELECT * FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun getAllInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<List<Record>>

    @Query("""
    SELECT 
        date(startTime / 1000, 'unixepoch', 'localtime') as day, 
        SUM(value) as totalValue,
        SUM(secondaryValue) as totalValue2 
    FROM Record 
    WHERE type = :type 
      AND startTime >= :startTime 
      AND endTime <= :endTime
    GROUP BY day
    ORDER BY day ASC
""")
    suspend fun getDailySums(
        type: RecordType,
        startTime: kotlin.time.Instant,
        endTime: kotlin.time.Instant
    ): List<DailySum>

    @Query("""
    SELECT 
        strftime('%Y-%m-%d %H:00', startTime / 1000, 'unixepoch', 'localtime') AS hourBlock, 
        SUM(value) AS totalValue,
        SUM(secondaryValue) AS totalValue2
    FROM Record 
    WHERE type = :type 
      AND startTime >= :startTime 
      AND endTime <= :endTime
    GROUP BY hourBlock
    ORDER BY hourBlock ASC
""")
    suspend fun getHourlySums(
        type: RecordType,
        startTime: Long,
        endTime: Long
    ): List<HourlySum>

    @Query("""
    SELECT 
        date(startTime / 1000, 'unixepoch', 'localtime') as day, 
        AVG(value) as totalValue,
        AVG(secondaryValue) as totalValue2 
    FROM Record 
    WHERE type = :type 
      AND startTime >= :startTime 
      AND endTime <= :endTime
    GROUP BY day
    ORDER BY day ASC
""")
    suspend fun getDailyAvgs(
        type: RecordType,
        startTime: kotlin.time.Instant,
        endTime: kotlin.time.Instant
    ): List<DailySum>

    @Query("""
    SELECT 
        strftime('%Y-%m-%d %H:00', startTime / 1000, 'unixepoch', 'localtime') AS hourBlock, 
        AVG(value) AS totalValue,
        AVG(secondaryValue) AS totalValue2
    FROM Record 
    WHERE type = :type 
      AND startTime >= :startTime 
      AND endTime <= :endTime
    GROUP BY hourBlock
    ORDER BY hourBlock ASC
""")
    suspend fun getHourlyAvgs(
        type: RecordType,
        startTime: Long,
        endTime: Long
    ): List<HourlySum>

    // Helper data class to catch the results
    data class DailySum(
        val day: String, // Format: YYYY-MM-DD
        val totalValue: Double,
        val totalValue2: Double
    )
    data class HourlySum(
        val hourBlock: String, // Format: 2026-03-03 15:00
        val totalValue: Double,
        val totalValue2: Double
    )

    // Food Logger Methods
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIngredient(ingredient: Ingredient)

    @Update
    suspend fun updateIngredient(ingredient: Ingredient)

    @Delete
    suspend fun deleteIngredient(ingredient: Ingredient)

    @Query("SELECT * FROM Ingredient ORDER BY originalName ASC")
    fun getAllIngredientsFlow(): Flow<List<Ingredient>>

    @Query("SELECT * FROM Ingredient WHERE isRecipe = 1 ORDER BY originalName ASC")
    fun getIngredientsAsRecipesFlow(): Flow<List<Ingredient>>

    @Query("SELECT * FROM Ingredient WHERE id = :id")
    suspend fun getIngredient(id: String): Ingredient?

    @Query("SELECT * FROM Ingredient WHERE originalName LIKE '%' || :query || '%' OR customName LIKE '%' || :query || '%'")
    suspend fun searchIngredients(query: String): List<Ingredient>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipe(recipe: Recipe)

    @Update
    suspend fun updateRecipe(recipe: Recipe)

    @Delete
    suspend fun deleteRecipe(recipe: Recipe)

    @Query("SELECT * FROM Recipe ORDER BY name ASC")
    fun getAllRecipesFlow(): Flow<List<Recipe>>

    @Query("SELECT * FROM Recipe WHERE id = :id")
    suspend fun getRecipe(id: String): Recipe?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServingUnit(unit: ServingUnit)

    @Delete
    suspend fun deleteServingUnit(unit: ServingUnit)

    @Query("SELECT * FROM ServingUnit WHERE ingredientId = :ingredientId")
    suspend fun getUnitsForIngredient(ingredientId: String): List<ServingUnit>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipeIngredient(recipeIngredient: RecipeIngredient)

    @Delete
    suspend fun deleteRecipeIngredient(recipeIngredient: RecipeIngredient)

    @Query("SELECT * FROM RecipeIngredient WHERE recipeId = :recipeId")
    suspend fun getIngredientsForRecipe(recipeId: String): List<RecipeIngredient>
}

@Database(
    entities = [Record::class, Ingredient::class, Recipe::class, ServingUnit::class, RecipeIngredient::class],
    version = 5,
    exportSchema = false
)
@ColumnTypeConverters(Converters::class)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun healthDao(): HealthDao

    companion object : com.vayunmathur.library.util.DatabaseMigrations {
        override val migrations = listOf(
            Migration(4, 5) {
                it.execSQL("ALTER TABLE Record ADD COLUMN exercise_exerciseType INTEGER NOT NULL DEFAULT 0")
                it.execSQL("ALTER TABLE Record ADD COLUMN exercise_title TEXT")
                it.execSQL("ALTER TABLE Record ADD COLUMN exercise_notes TEXT")
                it.execSQL("ALTER TABLE Record ADD COLUMN exercise_segmentsJson TEXT")
                it.execSQL("ALTER TABLE Record ADD COLUMN exercise_lapsJson TEXT")
                it.execSQL("ALTER TABLE Record ADD COLUMN exercise_hasRoute INTEGER NOT NULL DEFAULT 0")
            }
        )
    }
}

class Converters {
    @ColumnTypeConverter
    fun fromTimestamp(value: Long?): Instant? = value?.let { Instant.ofEpochMilli(it) }

    @ColumnTypeConverter
    fun dateToTimestamp(date: Instant?): Long? = date?.toEpochMilli()

    @ColumnTypeConverter
    fun toTS(date: kotlin.time.Instant): Long = date.toEpochMilliseconds()

    @ColumnTypeConverter
    fun fromTS(timestamp: Long): kotlin.time.Instant = kotlin.time.Instant.fromEpochMilliseconds(timestamp)
}
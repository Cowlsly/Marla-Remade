package com.vayunmathur.health.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/**
 * Single owner of [HealthDatabase]. Replaces every `buildDatabase<HealthDatabase>()`
 * call site (MainActivity, HealthSyncWorker) and the global `HealthAPI.db` lateinit.
 */
class HealthRepository private constructor(context: Context) :
    RoomRepository<HealthDatabase>(context, HealthDatabase::class) {

    private val dao get() = db.healthDao()

    // ------------------------------------------------------------------
    // Flow passthroughs — keep DAO names so callers migrating off `HealthAPI.db.healthDao()` stay trivial
    // ------------------------------------------------------------------

    fun getRecordsFlow(type: RecordType): Flow<List<Record>> = dao.getRecordsFlow(type)
    suspend fun getLastRecord(type: RecordType): Record? = dao.getLastRecord(type)
    fun sumInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double> =
        dao.sumInRange(type, startTime, endTime)
    fun sumNutritionInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<NutritionData> =
        dao.sumNutritionInRange(type, startTime, endTime)
    fun minInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double?> =
        dao.minInRange(type, startTime, endTime)
    fun maxInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double?> =
        dao.maxInRange(type, startTime, endTime)
    fun getAllInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<List<Record>> =
        dao.getAllInRange(type, startTime, endTime)
    suspend fun getDailySums(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): List<HealthDao.DailySum> =
        dao.getDailySums(type, startTime, endTime)
    suspend fun getHourlySums(type: RecordType, startTime: Long, endTime: Long): List<HealthDao.HourlySum> =
        dao.getHourlySums(type, startTime, endTime)
    suspend fun getDailyAvgs(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): List<HealthDao.DailySum> =
        dao.getDailyAvgs(type, startTime, endTime)
    suspend fun getHourlyAvgs(type: RecordType, startTime: Long, endTime: Long): List<HealthDao.HourlySum> =
        dao.getHourlyAvgs(type, startTime, endTime)

    suspend fun upsert(records: List<Record>) = dao.upsert(records)
    suspend fun deleteByIds(ids: List<String>) = dao.deleteByIds(ids)

    // Ingredients / Recipes
    suspend fun insertIngredient(ingredient: Ingredient) = dao.insertIngredient(ingredient)
    suspend fun updateIngredient(ingredient: Ingredient) = dao.updateIngredient(ingredient)
    suspend fun deleteIngredient(ingredient: Ingredient) = dao.deleteIngredient(ingredient)
    fun getAllIngredientsFlow(): Flow<List<Ingredient>> = dao.getAllIngredientsFlow()
    fun getIngredientsAsRecipesFlow(): Flow<List<Ingredient>> = dao.getIngredientsAsRecipesFlow()
    suspend fun getIngredient(id: String): Ingredient? = dao.getIngredient(id)
    suspend fun searchIngredients(query: String): List<Ingredient> = dao.searchIngredients(query)

    suspend fun insertRecipe(recipe: Recipe) = dao.insertRecipe(recipe)
    suspend fun updateRecipe(recipe: Recipe) = dao.updateRecipe(recipe)
    suspend fun deleteRecipe(recipe: Recipe) = dao.deleteRecipe(recipe)
    fun getAllRecipesFlow(): Flow<List<Recipe>> = dao.getAllRecipesFlow()
    suspend fun getRecipe(id: String): Recipe? = dao.getRecipe(id)

    suspend fun insertServingUnit(unit: ServingUnit) = dao.insertServingUnit(unit)
    suspend fun deleteServingUnit(unit: ServingUnit) = dao.deleteServingUnit(unit)
    suspend fun getUnitsForIngredient(ingredientId: String): List<ServingUnit> = dao.getUnitsForIngredient(ingredientId)

    suspend fun insertRecipeIngredient(recipeIngredient: RecipeIngredient) = dao.insertRecipeIngredient(recipeIngredient)
    suspend fun deleteRecipeIngredient(recipeIngredient: RecipeIngredient) = dao.deleteRecipeIngredient(recipeIngredient)
    suspend fun getIngredientsForRecipe(recipeId: String): List<RecipeIngredient> = dao.getIngredientsForRecipe(recipeId)

    /** Expose underlying [HealthDatabase] for call sites that need transactional access (prefer adding a method here instead). */
    internal val database: HealthDatabase get() = db

    companion object {
        @Volatile
        private var instance: HealthRepository? = null

        fun get(context: Context): HealthRepository =
            instance ?: synchronized(this) {
                instance ?: HealthRepository(context).also { instance = it }
            }
    }
}

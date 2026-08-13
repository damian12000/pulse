package com.pulse.core.data

import com.pulse.core.database.dao.DailyTotals
import com.pulse.core.database.dao.DiaryDao
import com.pulse.core.database.dao.FoodDao
import com.pulse.core.database.dao.MealTotals
import com.pulse.core.database.dao.WaterDao
import com.pulse.core.database.entity.DiaryEntryEntity
import com.pulse.core.database.entity.WaterEntryEntity
import com.pulse.core.model.FoodNutrition
import com.pulse.core.model.Nutrition
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

interface DiaryRepository {
    fun observeDay(date: Long): Flow<List<DiaryEntryEntity>>
    fun observeMeal(date: Long, mealType: String): Flow<List<DiaryEntryEntity>>
    fun observeDailyTotals(date: Long): Flow<DailyTotals>
    fun observeMealTotals(date: Long): Flow<List<MealTotals>>

    suspend fun logFood(
        date: Long,
        mealType: String,
        foodId: String,
        servingId: String?,
        quantity: Double,
    ): String

    suspend fun updateQuantity(entryId: String, quantity: Double)
    suspend fun deleteEntry(entryId: String)
    suspend fun copyMeal(fromDate: Long, fromMeal: String, toDate: Long, toMeal: String): Int
}

@Singleton
class DefaultDiaryRepository @Inject constructor(
    private val diaryDao: DiaryDao,
    private val foodDao: FoodDao,
    private val clock: Clock = Clock.System,
) : DiaryRepository {

    override fun observeDay(date: Long) = diaryDao.observeDay(date)
    override fun observeMeal(date: Long, mealType: String) = diaryDao.observeMeal(date, mealType)
    override fun observeDailyTotals(date: Long) = diaryDao.observeDailyTotals(date)
    override fun observeMealTotals(date: Long) = diaryDao.observeMealTotals(date)

    /**
     * Logs a food.
     *
     * Nutrition is resolved and **snapshotted onto the entry** rather than
     * joined at read time, so a later database refresh or food edit cannot
     * rewrite what was eaten (PHASE2_ARCHITECTURE.md §4.3).
     */
    override suspend fun logFood(
        date: Long,
        mealType: String,
        foodId: String,
        servingId: String?,
        quantity: Double,
    ): String {
        require(quantity > 0) { "quantity must be positive, was $quantity" }

        val food = foodDao.findById(foodId)
            ?: error("cannot log unknown food $foodId")
        val servings = foodDao.servingsFor(foodId)

        // Fall back sensibly: the requested serving, else the default, else the
        // first, else treat the quantity as grams.
        val serving = servingId?.let { id -> servings.firstOrNull { it.id == id } }
            ?: servings.firstOrNull { it.isDefault }
            ?: servings.firstOrNull()

        val grams = (serving?.gramWeight ?: 1.0) * quantity
        val nutrition = food.toFoodNutrition().forAmount(grams)

        val now = clock.nowMillis()
        val id = UUID.randomUUID().toString()

        diaryDao.upsert(
            DiaryEntryEntity(
                id = id,
                date = date,
                mealType = mealType,
                foodId = foodId,
                servingId = serving?.id,
                quantity = quantity,
                grams = grams,
                kcal = nutrition.kcal,
                protein = nutrition.proteinG,
                carbs = nutrition.carbsG,
                fat = nutrition.fatG,
                fiber = nutrition.fiberG,
                sugar = nutrition.sugarG,
                satFat = nutrition.satFatG,
                sodiumMg = nutrition.sodiumMg,
                loggedAt = now,
                createdAt = now,
                updatedAt = now,
            ),
        )

        foodDao.recordUse(foodId, now)
        return id
    }

    /**
     * Rescales an entry in place.
     *
     * Deliberately scales the **stored snapshot** rather than re-reading the
     * food: correcting "2 servings" to "3" must not also silently pull in an
     * upstream nutrition change the user never saw.
     */
    override suspend fun updateQuantity(entryId: String, quantity: Double) {
        require(quantity > 0) { "quantity must be positive, was $quantity" }
        val entry = diaryDao.findById(entryId) ?: return
        val factor = quantity / entry.quantity

        diaryDao.upsert(
            entry.copy(
                quantity = quantity,
                grams = entry.grams * factor,
                kcal = entry.kcal * factor,
                protein = entry.protein * factor,
                carbs = entry.carbs * factor,
                fat = entry.fat * factor,
                fiber = entry.fiber?.times(factor),
                sugar = entry.sugar?.times(factor),
                satFat = entry.satFat?.times(factor),
                sodiumMg = entry.sodiumMg?.times(factor),
                updatedAt = clock.nowMillis(),
            ),
        )
    }

    override suspend fun deleteEntry(entryId: String) =
        diaryDao.softDelete(entryId, clock.nowMillis())

    /**
     * Copies a meal to another day — "same breakfast as yesterday" in one tap.
     * Copies the snapshots, so the copy reflects what was actually eaten.
     */
    override suspend fun copyMeal(
        fromDate: Long,
        fromMeal: String,
        toDate: Long,
        toMeal: String,
    ): Int {
        val source = diaryDao.observeMeal(fromDate, fromMeal).firstValue()
        if (source.isEmpty()) return 0

        val now = clock.nowMillis()
        diaryDao.upsertAll(
            source.map { entry ->
                entry.copy(
                    id = UUID.randomUUID().toString(),
                    date = toDate,
                    mealType = toMeal,
                    loggedAt = now,
                    createdAt = now,
                    updatedAt = now,
                )
            },
        )
        return source.size
    }
}

interface WaterRepository {
    fun observeDayTotal(date: Long): Flow<Int>
    fun observeDay(date: Long): Flow<List<WaterEntryEntity>>
    suspend fun add(date: Long, amountMl: Int): String
    suspend fun remove(entryId: String)
}

@Singleton
class DefaultWaterRepository @Inject constructor(
    private val waterDao: WaterDao,
    private val clock: Clock = Clock.System,
) : WaterRepository {

    override fun observeDayTotal(date: Long) = waterDao.observeDayTotal(date)
    override fun observeDay(date: Long) = waterDao.observeDay(date)

    override suspend fun add(date: Long, amountMl: Int): String {
        require(amountMl > 0) { "amount must be positive, was $amountMl" }
        val now = clock.nowMillis()
        val id = UUID.randomUUID().toString()
        waterDao.upsert(
            WaterEntryEntity(
                id = id,
                date = date,
                amountMl = amountMl,
                loggedAt = now,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    override suspend fun remove(entryId: String) =
        waterDao.softDelete(entryId, clock.nowMillis())
}

/** Maps the persisted per-100 columns onto the domain type. */
internal fun com.pulse.core.database.entity.FoodEntity.toFoodNutrition() = FoodNutrition(
    kcalPer100 = kcalPer100,
    proteinPer100 = proteinPer100,
    carbsPer100 = carbsPer100,
    fatPer100 = fatPer100,
    fiberPer100 = fiberPer100,
    sugarPer100 = sugarPer100,
    satFatPer100 = satFatPer100,
    sodiumMgPer100 = sodiumMgPer100,
    cholesterolMgPer100 = cholesterolMgPer100,
    potassiumMgPer100 = potassiumMgPer100,
)

internal fun DiaryEntryEntity.toNutrition() = Nutrition(
    kcal = kcal,
    proteinG = protein,
    carbsG = carbs,
    fatG = fat,
    fiberG = fiber,
    sugarG = sugar,
    satFatG = satFat,
    sodiumMg = sodiumMg,
)

private suspend fun <T> Flow<List<T>>.firstValue(): List<T> = first()

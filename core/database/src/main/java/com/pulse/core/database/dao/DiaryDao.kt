package com.pulse.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.pulse.core.database.entity.DiaryEntryEntity
import com.pulse.core.database.entity.WaterEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Daily totals. Computed on demand rather than materialized — with the
 * `(date, mealType)` index and the denormalized snapshot columns this is a
 * covering scan over a handful of rows, and a materialized table would add
 * cache-invalidation bugs for no measurable gain (PHASE2_ARCHITECTURE.md §4.6).
 */
data class DailyTotals(
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val fiber: Double,
    val sugar: Double,
    val satFat: Double,
    val sodiumMg: Double,
)

data class MealTotals(
    val mealType: String,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
)

@Dao
interface DiaryDao {

    @Query(
        """
        SELECT * FROM diary_entry
        WHERE date = :date AND isDeleted = 0
        ORDER BY
            CASE mealType
                WHEN 'BREAKFAST' THEN 0 WHEN 'LUNCH' THEN 1
                WHEN 'DINNER' THEN 2 ELSE 3
            END,
            sortOrder, loggedAt
        """
    )
    fun observeDay(date: Long): Flow<List<DiaryEntryEntity>>

    @Query(
        """
        SELECT * FROM diary_entry
        WHERE date = :date AND mealType = :mealType AND isDeleted = 0
        ORDER BY sortOrder, loggedAt
        """
    )
    fun observeMeal(date: Long, mealType: String): Flow<List<DiaryEntryEntity>>

    @Query(
        """
        SELECT
            COALESCE(SUM(kcal), 0)      AS kcal,
            COALESCE(SUM(protein), 0)   AS protein,
            COALESCE(SUM(carbs), 0)     AS carbs,
            COALESCE(SUM(fat), 0)       AS fat,
            COALESCE(SUM(fiber), 0)     AS fiber,
            COALESCE(SUM(sugar), 0)     AS sugar,
            COALESCE(SUM(satFat), 0)    AS satFat,
            COALESCE(SUM(sodiumMg), 0)  AS sodiumMg
        FROM diary_entry
        WHERE date = :date AND isDeleted = 0
        """
    )
    fun observeDailyTotals(date: Long): Flow<DailyTotals>

    @Query(
        """
        SELECT
            mealType                    AS mealType,
            COALESCE(SUM(kcal), 0)      AS kcal,
            COALESCE(SUM(protein), 0)   AS protein,
            COALESCE(SUM(carbs), 0)     AS carbs,
            COALESCE(SUM(fat), 0)       AS fat
        FROM diary_entry
        WHERE date = :date AND isDeleted = 0
        GROUP BY mealType
        """
    )
    fun observeMealTotals(date: Long): Flow<List<MealTotals>>

    /** Calories per day across a range — backs the trend charts. */
    @Query(
        """
        SELECT date AS date, COALESCE(SUM(kcal), 0) AS kcal
        FROM diary_entry
        WHERE date BETWEEN :from AND :to AND isDeleted = 0
        GROUP BY date ORDER BY date
        """
    )
    fun observeCaloriesBetween(from: Long, to: Long): Flow<List<DailyKcal>>

    @Query("SELECT * FROM diary_entry WHERE id = :id")
    suspend fun findById(id: String): DiaryEntryEntity?

    @Upsert
    suspend fun upsert(entry: DiaryEntryEntity)

    @Upsert
    suspend fun upsertAll(entries: List<DiaryEntryEntity>)

    /** Soft delete — a hard delete can't be propagated to another device. */
    @Query("UPDATE diary_entry SET isDeleted = 1, updatedAt = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    @Delete
    suspend fun hardDelete(entry: DiaryEntryEntity)

    @Query("SELECT COUNT(*) FROM diary_entry WHERE isDeleted = 0")
    suspend fun count(): Int
}

data class DailyKcal(val date: Long, val kcal: Double)

@Dao
interface WaterDao {

    @Query("SELECT * FROM water_entry WHERE date = :date AND isDeleted = 0 ORDER BY loggedAt")
    fun observeDay(date: Long): Flow<List<WaterEntryEntity>>

    @Query("SELECT COALESCE(SUM(amountMl), 0) FROM water_entry WHERE date = :date AND isDeleted = 0")
    fun observeDayTotal(date: Long): Flow<Int>

    @Query(
        """
        SELECT date AS date, COALESCE(SUM(amountMl), 0) AS totalMl
        FROM water_entry
        WHERE date BETWEEN :from AND :to AND isDeleted = 0
        GROUP BY date ORDER BY date
        """
    )
    fun observeBetween(from: Long, to: Long): Flow<List<DailyWater>>

    @Upsert
    suspend fun upsert(entry: WaterEntryEntity)

    @Query("UPDATE water_entry SET isDeleted = 1, updatedAt = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)
}

data class DailyWater(val date: Long, val totalMl: Int)

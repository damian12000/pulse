package com.pulse.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import com.pulse.core.database.entity.FoodEntity
import com.pulse.core.database.entity.FoodServingEntity
import com.pulse.core.database.entity.FoodUsageEntity
import kotlinx.coroutines.flow.Flow

/** A food with its servings — what the serving picker needs in one query. */
data class FoodWithServings(
    @Embedded val food: FoodEntity,
    @Relation(parentColumn = "id", entityColumn = "foodId")
    val servings: List<FoodServingEntity>,
)

@Dao
interface FoodDao {

    // --- barcode ------------------------------------------------------------

    /**
     * Resolve a barcode to the single best row.
     *
     * The barcode index is deliberately non-unique — the bundled dataset
     * contains 186 legitimate duplicates. Ties break by confidence first, then
     * preferring user-owned rows, so a correction the user made always wins
     * over the bundled original.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM food
        WHERE barcode = :barcode AND isDeleted = 0
        ORDER BY
            CASE WHEN source = 'USER' THEN 0 ELSE 1 END,
            CASE dataConfidence WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END
        LIMIT 1
        """
    )
    suspend fun findByBarcode(barcode: String): FoodWithServings?

    @Query("SELECT COUNT(*) FROM food WHERE barcode = :barcode AND isDeleted = 0")
    suspend fun countByBarcode(barcode: String): Int

    // --- search -------------------------------------------------------------

    /**
     * Full-text search.
     *
     * Ranking puts unbranded generics first: a bare query for "chicken breast"
     * should surface the generic, not the 400th branded product. User-created
     * foods outrank everything, then generics, then prepared/restaurant, then
     * the 313k grocery items.
     */
    @Transaction
    @Query(
        """
        SELECT f.* FROM food AS f
        JOIN food_fts ON food_fts.docid = f.rowid
        WHERE food_fts MATCH :query AND f.isDeleted = 0
        ORDER BY
            CASE WHEN f.source = 'USER' THEN 0 ELSE 1 END,
            CASE f.foodType
                WHEN 'everyday' THEN 0
                WHEN 'prepared' THEN 1
                WHEN 'restaurant' THEN 2
                ELSE 3
            END,
            CASE f.dataConfidence WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END,
            LENGTH(f.name)
        LIMIT :limit
        """
    )
    suspend fun search(query: String, limit: Int = 50): List<FoodWithServings>

    // --- single food --------------------------------------------------------

    @Transaction
    @Query("SELECT * FROM food WHERE id = :id AND isDeleted = 0")
    fun observeWithServings(id: String): Flow<FoodWithServings?>

    @Query("SELECT * FROM food WHERE id = :id")
    suspend fun findById(id: String): FoodEntity?

    @Query("SELECT * FROM food_serving WHERE foodId = :foodId ORDER BY sortOrder")
    suspend fun servingsFor(foodId: String): List<FoodServingEntity>

    // --- recent / frequent / favourites ------------------------------------

    @Transaction
    @Query(
        """
        SELECT f.* FROM food AS f
        JOIN food_usage AS u ON u.foodId = f.id
        WHERE f.isDeleted = 0
        ORDER BY u.lastUsedAt DESC
        LIMIT :limit
        """
    )
    fun observeRecent(limit: Int = 30): Flow<List<FoodWithServings>>

    @Transaction
    @Query(
        """
        SELECT f.* FROM food AS f
        JOIN food_usage AS u ON u.foodId = f.id
        WHERE f.isDeleted = 0 AND u.useCount > 0
        ORDER BY u.useCount DESC, u.lastUsedAt DESC
        LIMIT :limit
        """
    )
    fun observeFrequent(limit: Int = 30): Flow<List<FoodWithServings>>

    @Transaction
    @Query(
        """
        SELECT f.* FROM food AS f
        JOIN food_usage AS u ON u.foodId = f.id
        WHERE f.isDeleted = 0 AND u.isFavorite = 1
        ORDER BY u.lastUsedAt DESC
        """
    )
    fun observeFavorites(): Flow<List<FoodWithServings>>

    // --- writes -------------------------------------------------------------

    @Upsert
    suspend fun upsert(food: FoodEntity)

    @Upsert
    suspend fun upsertAll(foods: List<FoodEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServings(servings: List<FoodServingEntity>)

    @Upsert
    suspend fun upsertUsage(usage: FoodUsageEntity)

    @Query("SELECT * FROM food_usage WHERE foodId = :foodId")
    suspend fun usageFor(foodId: String): FoodUsageEntity?

    /** Bump recency/frequency. Called on every log action. */
    @Transaction
    suspend fun recordUse(foodId: String, at: Long) {
        val existing = usageFor(foodId)
        upsertUsage(
            existing?.copy(useCount = existing.useCount + 1, lastUsedAt = at)
                ?: FoodUsageEntity(foodId = foodId, useCount = 1, lastUsedAt = at),
        )
    }

    @Query("UPDATE food SET isDeleted = 1, updatedAt = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    // --- maintenance --------------------------------------------------------

    @Query("SELECT COUNT(*) FROM food WHERE isDeleted = 0")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM food WHERE source = :source AND isDeleted = 0")
    suspend fun countBySource(source: String): Int

    /**
     * Purge cached external data that nothing references. Safe because external
     * rows are re-fetchable and diary entries carry their own nutrition
     * snapshot — but rows still referenced are kept so history stays intact.
     */
    @Query(
        """
        DELETE FROM food
        WHERE source NOT IN ('USER', 'RECIPE')
          AND id NOT IN (SELECT foodId FROM diary_entry)
          AND id NOT IN (SELECT foodId FROM recipe_ingredient)
          AND id NOT IN (SELECT foodId FROM saved_meal_item)
        """
    )
    suspend fun purgeUnreferencedExternal(): Int
}

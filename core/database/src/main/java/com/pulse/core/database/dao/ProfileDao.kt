package com.pulse.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import com.pulse.core.database.entity.BodyMeasurementEntity
import com.pulse.core.database.entity.GoalTargetEntity
import com.pulse.core.database.entity.PendingLookupEntity
import com.pulse.core.database.entity.ProfileEntity
import com.pulse.core.database.entity.RecipeEntity
import com.pulse.core.database.entity.RecipeIngredientEntity
import com.pulse.core.database.entity.SavedMealEntity
import com.pulse.core.database.entity.SavedMealItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile WHERE id = :id")
    fun observe(id: String = ProfileEntity.SINGLETON_ID): Flow<ProfileEntity?>

    @Query("SELECT * FROM profile WHERE id = :id")
    suspend fun get(id: String = ProfileEntity.SINGLETON_ID): ProfileEntity?

    @Upsert
    suspend fun upsert(profile: ProfileEntity)
}

@Dao
interface GoalDao {
    /**
     * The target in force on a given day. `goal_target` is append-only and
     * effective-dated, so historical adherence stays honest when targets change.
     */
    @Query(
        """
        SELECT * FROM goal_target
        WHERE effectiveFrom <= :date AND isDeleted = 0
        ORDER BY effectiveFrom DESC LIMIT 1
        """
    )
    fun observeEffective(date: Long): Flow<GoalTargetEntity?>

    @Query(
        """
        SELECT * FROM goal_target
        WHERE effectiveFrom <= :date AND isDeleted = 0
        ORDER BY effectiveFrom DESC LIMIT 1
        """
    )
    suspend fun effectiveOn(date: Long): GoalTargetEntity?

    @Query("SELECT * FROM goal_target WHERE isDeleted = 0 ORDER BY effectiveFrom DESC")
    fun observeHistory(): Flow<List<GoalTargetEntity>>

    @Upsert
    suspend fun upsert(target: GoalTargetEntity)
}

@Dao
interface MeasurementDao {

    @Query(
        """
        SELECT * FROM body_measurement
        WHERE type = :type AND isDeleted = 0
        ORDER BY date DESC
        """
    )
    fun observeByType(type: String): Flow<List<BodyMeasurementEntity>>

    @Query(
        """
        SELECT * FROM body_measurement
        WHERE type = :type AND date BETWEEN :from AND :to AND isDeleted = 0
        ORDER BY date
        """
    )
    fun observeRange(type: String, from: Long, to: Long): Flow<List<BodyMeasurementEntity>>

    @Query(
        """
        SELECT * FROM body_measurement
        WHERE type = :type AND isDeleted = 0
        ORDER BY date DESC LIMIT 1
        """
    )
    fun observeLatest(type: String): Flow<BodyMeasurementEntity?>

    @Query("SELECT DISTINCT type FROM body_measurement WHERE isDeleted = 0")
    fun observeTrackedTypes(): Flow<List<String>>

    @Upsert
    suspend fun upsert(measurement: BodyMeasurementEntity)

    @Query("UPDATE body_measurement SET isDeleted = 1, updatedAt = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)
}

data class SavedMealWithItems(
    @Embedded val meal: SavedMealEntity,
    @Relation(parentColumn = "id", entityColumn = "savedMealId")
    val items: List<SavedMealItemEntity>,
)

data class RecipeWithIngredients(
    @Embedded val recipe: RecipeEntity,
    @Relation(parentColumn = "id", entityColumn = "recipeId")
    val ingredients: List<RecipeIngredientEntity>,
)

@Dao
interface SavedMealDao {
    @Transaction
    @Query("SELECT * FROM saved_meal WHERE isDeleted = 0 ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<SavedMealWithItems>>

    @Transaction
    @Query("SELECT * FROM saved_meal WHERE id = :id")
    suspend fun findById(id: String): SavedMealWithItems?

    @Upsert suspend fun upsert(meal: SavedMealEntity)
    @Upsert suspend fun upsertItems(items: List<SavedMealItemEntity>)

    @Query("UPDATE saved_meal SET isDeleted = 1, updatedAt = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)
}

@Dao
interface RecipeDao {
    @Transaction
    @Query("SELECT * FROM recipe WHERE isDeleted = 0 ORDER BY name")
    fun observeAll(): Flow<List<RecipeWithIngredients>>

    @Transaction
    @Query("SELECT * FROM recipe WHERE id = :id")
    suspend fun findById(id: String): RecipeWithIngredients?

    @Upsert suspend fun upsert(recipe: RecipeEntity)
    @Upsert suspend fun upsertIngredients(ingredients: List<RecipeIngredientEntity>)

    @Query("DELETE FROM recipe_ingredient WHERE recipeId = :recipeId")
    suspend fun clearIngredients(recipeId: String)

    @Query("UPDATE recipe SET isDeleted = 1, updatedAt = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)
}

@Dao
interface PendingLookupDao {
    @Query("SELECT * FROM pending_lookup ORDER BY createdAt LIMIT :limit")
    suspend fun pending(limit: Int = 50): List<PendingLookupEntity>

    @Upsert suspend fun upsert(lookup: PendingLookupEntity)

    @Query("DELETE FROM pending_lookup WHERE barcode = :barcode")
    suspend fun clear(barcode: String)

    @Query("SELECT COUNT(*) FROM pending_lookup")
    suspend fun count(): Int
}

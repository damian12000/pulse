package com.pulse.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A logged food.
 *
 * **The nutrition figures here are an immutable snapshot taken at log time**,
 * not a join onto `food` at read time (PHASE2_ARCHITECTURE.md §4.3). A bundled
 * database refresh, an upstream correction, or the user editing a food must
 * never silently rewrite what was eaten three weeks ago.
 *
 * It also makes daily totals a single indexed SUM with no joins.
 */
@Entity(
    tableName = "diary_entry",
    foreignKeys = [
        ForeignKey(
            entity = FoodEntity::class,
            parentColumns = ["id"],
            childColumns = ["foodId"],
        ),
    ],
    indices = [Index("date", "mealType"), Index("foodId")],
)
data class DiaryEntryEntity(
    @PrimaryKey val id: String,

    /** Local epoch day. Local, not UTC — "what did I eat on Tuesday" is a
     *  wall-clock question, and a UTC day boundary would misfile late meals. */
    val date: Long,
    val mealType: String,

    val foodId: String,
    val servingId: String? = null,
    /** Number of servings, e.g. 2.5 */
    val quantity: Double,
    /** Resolved grams (or ml), denormalized so totals need no serving lookup. */
    val grams: Double,

    // --- immutable snapshot -------------------------------------------------
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val fiber: Double? = null,
    val sugar: Double? = null,
    val satFat: Double? = null,
    val sodiumMg: Double? = null,

    val sortOrder: Int = 0,
    val loggedAt: Long,

    override val createdAt: Long,
    override val updatedAt: Long,
    override val isDeleted: Boolean = false,
    override val syncState: String = SyncState.SYNCED,
) : Syncable

@Entity(tableName = "water_entry", indices = [Index("date")])
data class WaterEntryEntity(
    @PrimaryKey val id: String,
    val date: Long,
    val amountMl: Int,
    val loggedAt: Long,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val isDeleted: Boolean = false,
    override val syncState: String = SyncState.SYNCED,
) : Syncable

/**
 * A saved meal is a **bundle** — logging it inserts several diary entries you
 * can then edit individually. Contrast with [RecipeEntity], which is one item.
 * That distinction is what keeps the logging path uniform.
 */
@Entity(tableName = "saved_meal")
data class SavedMealEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int = 0,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val isDeleted: Boolean = false,
    override val syncState: String = SyncState.SYNCED,
) : Syncable

@Entity(
    tableName = "saved_meal_item",
    foreignKeys = [
        ForeignKey(
            entity = SavedMealEntity::class,
            parentColumns = ["id"],
            childColumns = ["savedMealId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = FoodEntity::class,
            parentColumns = ["id"],
            childColumns = ["foodId"],
        ),
    ],
    indices = [Index("savedMealId"), Index("foodId")],
)
data class SavedMealItemEntity(
    @PrimaryKey val id: String,
    val savedMealId: String,
    val foodId: String,
    val servingId: String? = null,
    val quantity: Double,
    val sortOrder: Int = 0,
)

/**
 * A recipe is **one food**. Saving it writes a `food` row with
 * `source = RECIPE` whose nutrition is Σ(ingredients) ÷ [servingsYield], so it
 * searches, scales and logs exactly like anything else.
 */
@Entity(tableName = "recipe")
data class RecipeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val servingsYield: Double,
    val notes: String? = null,
    val imagePath: String? = null,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val isDeleted: Boolean = false,
    override val syncState: String = SyncState.SYNCED,
) : Syncable

@Entity(
    tableName = "recipe_ingredient",
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipeId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = FoodEntity::class,
            parentColumns = ["id"],
            childColumns = ["foodId"],
        ),
    ],
    indices = [Index("recipeId"), Index("foodId")],
)
data class RecipeIngredientEntity(
    @PrimaryKey val id: String,
    val recipeId: String,
    val foodId: String,
    val servingId: String? = null,
    val quantity: Double,
    val sortOrder: Int = 0,
)

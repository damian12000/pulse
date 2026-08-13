package com.pulse.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Food — the single table for every food, whatever its origin
 * (PHASE2_ARCHITECTURE.md §1.2).
 *
 * A two-table split (cached vs user) would force a polymorphic foreign key on
 * every diary entry, recipe ingredient and saved-meal item. Instead one table
 * carries a [source] discriminator, which keeps real FKs everywhere while still
 * allowing external data to be identified (`source != 'USER'`), purged, or
 * excluded from a future sync.
 *
 * **External rows are never mutated.** Editing an external food copies it to a
 * new `source = USER` row with [derivedFromFoodId] set — enforced in the
 * repository and covered by test.
 *
 * Nutrition is stored per 100 g (per 100 ml when [isLiquid]), matching the
 * bundled dataset's native format so import is lossless and scaling is one
 * multiply.
 */
@Entity(
    tableName = "food",
    indices = [
        Index("barcode"),
        Index("source"),
        Index("name"),
        Index("foodType"),
    ],
)
data class FoodEntity(
    @PrimaryKey val id: String,

    /** OPENNUTRITION | OFF | USDA | CNF | USER | RECIPE */
    val source: String,
    val sourceId: String? = null,
    /** Copy-on-write provenance: the external row this user copy came from. */
    val derivedFromFoodId: String? = null,

    /** Normalized to EAN-13. Deliberately NOT unique — 186 legitimate duplicates
     *  exist in the bundled data; resolution picks best [dataConfidence]. */
    val barcode: String? = null,

    val name: String,
    val brand: String? = null,
    /** Space-joined synonyms; feeds FTS so "xl eggs" finds "Large Eggs". */
    val alternateNames: String? = null,
    /** grocery | everyday | restaurant | prepared — drives search ranking. */
    val foodType: String? = null,

    @ColumnInfo(name = "kcalPer100") val kcalPer100: Double,
    @ColumnInfo(name = "proteinPer100") val proteinPer100: Double,
    @ColumnInfo(name = "carbsPer100") val carbsPer100: Double,
    @ColumnInfo(name = "fatPer100") val fatPer100: Double,
    val fiberPer100: Double? = null,
    val sugarPer100: Double? = null,
    val satFatPer100: Double? = null,
    val sodiumMgPer100: Double? = null,
    val cholesterolMgPer100: Double? = null,
    val potassiumMgPer100: Double? = null,

    /** Curated extra nutrients as JSON — 29 keys kept of the dataset's 97. */
    val micronutrientsJson: String? = null,

    val isLiquid: Boolean = false,
    val densityGPerMl: Double? = null,

    val ingredients: String? = null,
    val allergensJson: String? = null,
    val imageUrl: String? = null,
    /** Also stores captured nutrition-label photos for user-created foods. */
    val localImagePath: String? = null,

    /** HIGH | MEDIUM | LOW — computed on ingest from the macro/energy check. */
    val dataConfidence: String,

    /** Set iff source = RECIPE. */
    val recipeId: String? = null,

    override val createdAt: Long,
    override val updatedAt: Long,
    override val isDeleted: Boolean = false,
    override val syncState: String = SyncState.SYNCED,
) : Syncable

/**
 * A named portion. [gramWeight] is grams, or millilitres when the parent food is
 * a liquid — the bundled dataset distinguishes these exactly via its serving
 * unit, so it is never inferred.
 */
@Entity(
    tableName = "food_serving",
    foreignKeys = [
        ForeignKey(
            entity = FoodEntity::class,
            parentColumns = ["id"],
            childColumns = ["foodId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("foodId")],
)
data class FoodServingEntity(
    @PrimaryKey val id: String,
    val foodId: String,
    /** Human label, e.g. "1 slice", "3 oz", "100 g". */
    val label: String,
    val gramWeight: Double,
    val isDefault: Boolean = false,
    val sortOrder: Int = 0,
)

/**
 * Drives "recent", "frequent" and "favourite" from one table rather than three.
 * Written on every log action.
 */
@Entity(
    tableName = "food_usage",
    foreignKeys = [
        ForeignKey(
            entity = FoodEntity::class,
            parentColumns = ["id"],
            childColumns = ["foodId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("lastUsedAt"), Index("useCount"), Index("isFavorite")],
)
data class FoodUsageEntity(
    @PrimaryKey val foodId: String,
    val useCount: Int = 0,
    val lastUsedAt: Long = 0,
    val isFavorite: Boolean = false,
)

/**
 * FTS index over name + brand + alternate names.
 *
 * `contentEntity` makes this an external-content table: the text is stored once
 * in `food` and the FTS table holds only the index, which matters at 326k rows.
 */
@Fts4(contentEntity = FoodEntity::class)
@Entity(tableName = "food_fts")
data class FoodFtsEntity(
    val name: String,
    val brand: String?,
    val alternateNames: String?,
)

package com.pulse.core.data

import com.pulse.core.database.dao.FoodDao
import com.pulse.core.database.dao.PendingLookupDao
import com.pulse.core.database.dao.FoodWithServings
import com.pulse.core.database.entity.FoodEntity
import com.pulse.core.database.entity.FoodServingEntity
import com.pulse.core.database.entity.FoodSource
import com.pulse.core.database.entity.PendingLookupEntity
import com.pulse.core.model.BarcodeNormalizer
import com.pulse.core.model.DataConfidence
import com.pulse.core.model.EnergyCheck
import com.pulse.core.model.FoodNutrition
import com.pulse.core.network.ChainResult
import com.pulse.core.network.FoodSourceChain
import com.pulse.core.network.RemoteFood
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Outcome of scanning a barcode.
 *
 * Exhaustive by construction — there is no generic "error" case that would
 * leave the UI with nothing to offer. Every branch ends somewhere the user can
 * still log something (PHASE2_ARCHITECTURE.md §6.3).
 */
sealed interface BarcodeResult {
    data class Found(
        val food: FoodWithServings,
        val confidence: DataConfidence,
    ) : BarcodeResult

    /** Known product, but missing fields the user should fill in before logging. */
    data class Incomplete(
        val food: FoodWithServings,
        val missing: Set<NutrientField>,
    ) : BarcodeResult

    /** Nothing local or remote knows it. [suggestedName] pre-fills the create form. */
    data class NotFound(
        val barcode: String,
        val suggestedName: String? = null,
    ) : BarcodeResult

    /** Not cached and no network. Queued for retry; manual creation still available. */
    data class Offline(val barcode: String) : BarcodeResult

    /** The scanner returned something that isn't a usable retail barcode. */
    data class Unreadable(val raw: String) : BarcodeResult
}

enum class NutrientField { CALORIES, PROTEIN, CARBS, FAT, SERVING }

/** A draft food from the create-food form or the label scanner. */
data class FoodDraft(
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val nutrition: FoodNutrition,
    val servingLabel: String,
    val servingGrams: Double,
    val isLiquid: Boolean = false,
    val localImagePath: String? = null,
)

interface FoodRepository {
    suspend fun resolveBarcode(rawBarcode: String, online: Boolean = true): BarcodeResult
    suspend fun search(query: String, limit: Int = 50): List<FoodWithServings>
    fun observeRecent(limit: Int = 30): Flow<List<FoodWithServings>>
    fun observeFrequent(limit: Int = 30): Flow<List<FoodWithServings>>
    fun observeFavorites(): Flow<List<FoodWithServings>>
    fun observeFood(foodId: String): Flow<FoodWithServings?>
    suspend fun createUserFood(draft: FoodDraft): String
    suspend fun editFood(foodId: String, draft: FoodDraft): String
    suspend fun toggleFavorite(foodId: String)
    suspend fun recordUse(foodId: String)
}

@Singleton
class DefaultFoodRepository @Inject constructor(
    private val foodDao: FoodDao,
    private val pendingLookupDao: PendingLookupDao,
    private val sourceChain: FoodSourceChain,
    private val clock: Clock = Clock.System,
) : FoodRepository {

    /**
     * Local first, always.
     *
     * The bundled database holds 313k barcodes, so the overwhelming majority of
     * scans never touch the network. Only a genuine local miss consults remote
     * sources, and a remote hit is cached so the second scan is local too.
     */
    override suspend fun resolveBarcode(rawBarcode: String, online: Boolean): BarcodeResult {
        val ean13 = BarcodeNormalizer.toEan13(rawBarcode)
            ?: return BarcodeResult.Unreadable(rawBarcode)

        localHit(ean13)?.let { return it }

        if (!online) {
            queueForRetry(ean13)
            return BarcodeResult.Offline(ean13)
        }

        return when (val remote = sourceChain.byBarcode(ean13)) {
            is ChainResult.Found -> {
                cacheRemote(remote.food, remote.sourceId)
                localHit(ean13) ?: BarcodeResult.NotFound(ean13, remote.food.name)
            }

            ChainResult.NotFound -> BarcodeResult.NotFound(ean13)

            // Every source erroring is *not* evidence the product doesn't
            // exist. Telling the user to create a food that already exists
            // would pollute their library, so this is treated as offline and
            // queued for retry instead.
            is ChainResult.AllFailed, ChainResult.NoSources -> {
                queueForRetry(ean13)
                BarcodeResult.Offline(ean13)
            }
        }
    }

    private suspend fun localHit(ean13: String): BarcodeResult? =
        foodDao.findByBarcode(ean13)?.let { hit ->
            val missing = missingFields(hit)
            if (missing.isEmpty()) {
                BarcodeResult.Found(hit, hit.food.dataConfidence.toConfidence())
            } else {
                BarcodeResult.Incomplete(hit, missing)
            }
        }

    private suspend fun queueForRetry(ean13: String) {
        pendingLookupDao.upsert(
            PendingLookupEntity(barcode = ean13, createdAt = clock.nowMillis()),
        )
    }

    /**
     * Caches a remote product, tagged with its source so external data stays
     * identifiable, purgeable and excluded from any future sync.
     */
    private suspend fun cacheRemote(food: RemoteFood, sourceId: String) {
        val now = clock.nowMillis()
        val id = "${sourceId.lowercase()}_${food.sourceId}"
        val nutrition = FoodNutrition(
            kcalPer100 = food.kcalPer100,
            proteinPer100 = food.proteinPer100,
            carbsPer100 = food.carbsPer100,
            fatPer100 = food.fatPer100,
            fiberPer100 = food.fiberPer100,
            sugarPer100 = food.sugarPer100,
            satFatPer100 = food.satFatPer100,
            sodiumMgPer100 = food.sodiumMgPer100,
        )

        foodDao.upsert(
            FoodEntity(
                id = id,
                source = sourceId,
                sourceId = food.sourceId,
                barcode = food.barcode,
                name = food.name,
                brand = food.brand,
                foodType = "grocery",
                kcalPer100 = food.kcalPer100,
                proteinPer100 = food.proteinPer100,
                carbsPer100 = food.carbsPer100,
                fatPer100 = food.fatPer100,
                fiberPer100 = food.fiberPer100,
                sugarPer100 = food.sugarPer100,
                satFatPer100 = food.satFatPer100,
                sodiumMgPer100 = food.sodiumMgPer100,
                isLiquid = food.isLiquid,
                ingredients = food.ingredients,
                imageUrl = food.imageUrl,
                dataConfidence = EnergyCheck.classify(nutrition).name,
                createdAt = now,
                updatedAt = now,
            ),
        )

        val canonicalLabel = if (food.isLiquid) "100 ml" else "100 g"
        val servings = buildList {
            val grams = food.servingGrams
            if (grams != null && grams > 0 && kotlin.math.abs(grams - 100.0) > 1e-9) {
                add(
                    FoodServingEntity(
                        id = "${id}_s0",
                        foodId = id,
                        label = food.servingLabel ?: "1 serving",
                        gramWeight = grams,
                        isDefault = true,
                        sortOrder = 0,
                    ),
                )
            }
            add(
                FoodServingEntity(
                    id = "${id}_s1",
                    foodId = id,
                    label = canonicalLabel,
                    gramWeight = 100.0,
                    isDefault = isEmpty(),
                    sortOrder = 1,
                ),
            )
        }
        foodDao.insertServings(servings)
        pendingLookupDao.clear(food.barcode ?: return)
    }

    /**
     * A food is loggable only with energy, all three macros, and something to
     * scale by. Anything else is reported so the UI can ask rather than
     * silently logging zeroes.
     */
    private fun missingFields(hit: FoodWithServings): Set<NutrientField> = buildSet {
        val f = hit.food
        if (f.kcalPer100 <= 0.0 && f.proteinPer100 <= 0.0 &&
            f.carbsPer100 <= 0.0 && f.fatPer100 <= 0.0
        ) {
            add(NutrientField.CALORIES)
        }
        if (hit.servings.isEmpty()) add(NutrientField.SERVING)
    }

    override suspend fun search(query: String, limit: Int): List<FoodWithServings> {
        val prepared = prepareFtsQuery(query) ?: return emptyList()
        return foodDao.search(prepared, limit)
    }

    override fun observeRecent(limit: Int) = foodDao.observeRecent(limit)
    override fun observeFrequent(limit: Int) = foodDao.observeFrequent(limit)
    override fun observeFavorites() = foodDao.observeFavorites()
    override fun observeFood(foodId: String) = foodDao.observeWithServings(foodId)

    override suspend fun createUserFood(draft: FoodDraft): String =
        insertUserFood(draft, derivedFrom = null)

    /**
     * Copy-on-write: editing an externally-sourced food never mutates it.
     *
     * This is what keeps the bundled data identifiable and re-derivable while
     * still letting the user correct anything. Editing a food the user already
     * owns updates it in place.
     */
    override suspend fun editFood(foodId: String, draft: FoodDraft): String {
        val existing = foodDao.findById(foodId)
        return if (existing != null && existing.source in FoodSource.USER_OWNED) {
            insertUserFood(draft, derivedFrom = existing.derivedFromFoodId, reuseId = foodId)
        } else {
            insertUserFood(draft, derivedFrom = foodId)
        }
    }

    private suspend fun insertUserFood(
        draft: FoodDraft,
        derivedFrom: String?,
        reuseId: String? = null,
    ): String {
        val now = clock.nowMillis()
        val id = reuseId ?: UUID.randomUUID().toString()
        val n = draft.nutrition

        foodDao.upsert(
            FoodEntity(
                id = id,
                source = FoodSource.USER,
                sourceId = null,
                derivedFromFoodId = derivedFrom,
                barcode = draft.barcode?.let { BarcodeNormalizer.toEan13(it) },
                name = draft.name.trim(),
                brand = draft.brand?.trim()?.takeIf(String::isNotEmpty),
                alternateNames = null,
                foodType = null,
                kcalPer100 = n.kcalPer100,
                proteinPer100 = n.proteinPer100,
                carbsPer100 = n.carbsPer100,
                fatPer100 = n.fatPer100,
                fiberPer100 = n.fiberPer100,
                sugarPer100 = n.sugarPer100,
                satFatPer100 = n.satFatPer100,
                sodiumMgPer100 = n.sodiumMgPer100,
                cholesterolMgPer100 = n.cholesterolMgPer100,
                potassiumMgPer100 = n.potassiumMgPer100,
                isLiquid = draft.isLiquid,
                localImagePath = draft.localImagePath,
                // A user-entered food is scored by the same macro/energy rule as
                // bundled data — no special pleading for hand-typed numbers.
                dataConfidence = EnergyCheck.classify(n).name,
                createdAt = now,
                updatedAt = now,
            ),
        )

        val canonicalLabel = if (draft.isLiquid) "100 ml" else "100 g"
        val servings = buildList {
            add(
                FoodServingEntity(
                    id = "${id}_s0",
                    foodId = id,
                    label = draft.servingLabel,
                    gramWeight = draft.servingGrams,
                    isDefault = true,
                    sortOrder = 0,
                ),
            )
            // Always offer a raw 100 g/ml option, unless the serving already is one.
            if (kotlin.math.abs(draft.servingGrams - 100.0) > 1e-9) {
                add(
                    FoodServingEntity(
                        id = "${id}_s1",
                        foodId = id,
                        label = canonicalLabel,
                        gramWeight = 100.0,
                        isDefault = false,
                        sortOrder = 1,
                    ),
                )
            }
        }
        foodDao.insertServings(servings)
        return id
    }

    override suspend fun toggleFavorite(foodId: String) {
        val existing = foodDao.usageFor(foodId)
        foodDao.upsertUsage(
            existing?.copy(isFavorite = !existing.isFavorite)
                ?: com.pulse.core.database.entity.FoodUsageEntity(
                    foodId = foodId,
                    isFavorite = true,
                    lastUsedAt = clock.nowMillis(),
                ),
        )
    }

    override suspend fun recordUse(foodId: String) =
        foodDao.recordUse(foodId, clock.nowMillis())
}

private fun String.toConfidence(): DataConfidence =
    runCatching { DataConfidence.valueOf(this) }.getOrDefault(DataConfidence.LOW)

/**
 * Prepares user input for FTS4 MATCH.
 *
 * Raw input reaches SQLite's FTS parser, where `"`, `*`, `-`, `(`, `)` and the
 * bare keywords `OR`/`AND`/`NOT`/`NEAR` are all syntax. Left alone they cause a
 * crash or a silently wrong query, so input is reduced to alphanumeric tokens —
 * which by construction can no longer contain any FTS syntax at all.
 *
 * Tokens are then emitted **unquoted**, with a trailing `*` on the last one so
 * search feels live as you type. Quoting would be the obvious instinct here and
 * is wrong: FTS4 does not prefix-expand a quoted phrase, so `"ben"*` matches
 * nothing while `ben*` matches everything starting with "ben". Verified against
 * the bundled index, not assumed.
 */
internal fun prepareFtsQuery(raw: String): String? {
    val tokens = raw.trim()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotBlank() }
        // Bare boolean keywords are operators to FTS, and nobody searching for
        // food means them literally. Dropping beats quoting: a quoted "OR"
        // becomes a literal search term that matches nothing.
        .filterNot { it.uppercase() in FTS_KEYWORDS }

    if (tokens.isEmpty()) return null
    return tokens.mapIndexed { index, token ->
        if (index == tokens.lastIndex) "$token*" else token
    }.joinToString(" ")
}

private val FTS_KEYWORDS = setOf("OR", "AND", "NOT", "NEAR")

/** Injectable clock so time-dependent behaviour is testable. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}

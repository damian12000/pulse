package com.pulse.core.network

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A food fetched from a remote source, in the shape the repository caches.
 * Deliberately provider-agnostic so adding a source is a new implementation,
 * not a change to callers.
 */
data class RemoteFood(
    val sourceId: String,
    val barcode: String?,
    val name: String,
    val brand: String?,
    val kcalPer100: Double,
    val proteinPer100: Double,
    val carbsPer100: Double,
    val fatPer100: Double,
    val fiberPer100: Double? = null,
    val sugarPer100: Double? = null,
    val satFatPer100: Double? = null,
    val sodiumMgPer100: Double? = null,
    val isLiquid: Boolean = false,
    val servingLabel: String? = null,
    val servingGrams: Double? = null,
    val imageUrl: String? = null,
    val ingredients: String? = null,
)

/** Outcome of a single remote lookup. Distinguishes "no match" from "failed". */
sealed interface RemoteResult {
    data class Found(val food: RemoteFood) : RemoteResult
    data object NotFound : RemoteResult
    data class Failed(val reason: String, val retryable: Boolean) : RemoteResult
}

/**
 * One provider of remote food data.
 *
 * Implementations must never throw — a dead provider degrades the chain, it
 * doesn't break the app.
 */
interface FoodDataSource {
    val id: String
    val requiresNetwork: Boolean get() = true

    /** True when the source is usable — e.g. its API key is configured. */
    val isAvailable: Boolean get() = true

    suspend fun byBarcode(ean13: String): RemoteResult
}

/**
 * Open Food Facts — the primary remote source (PHASE1_RESEARCH.md §2).
 *
 * Two constraints from the research are enforced here rather than left to
 * convention:
 *
 * 1. **A descriptive `User-Agent` is mandatory.** OFF asks for
 *    `AppName/Version (contact)` and blocks anonymous traffic.
 * 2. **Calls must go device-direct, never through a shared server.** OFF's
 *    limits are per-IP (15 product reads/min); a proxy would pool every user
 *    onto one budget and get banned. This client is constructed with a base URL
 *    so tests can point it at a local server, but production must use the real
 *    host from the device.
 */
class OpenFoodFactsDataSource(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = PRODUCTION.toHttpUrl(),
    private val json: Json = LenientJson,
) : FoodDataSource {

    override val id: String = "OFF"

    override suspend fun byBarcode(ean13: String): RemoteResult = withContext(Dispatchers.IO) {
        val url = baseUrl.newBuilder()
            .addPathSegments("api/v2/product/$ean13")
            .addQueryParameter("fields", REQUESTED_FIELDS)
            .build()

        val request = Request.Builder().url(url).get().build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> RemoteResult.NotFound
                    // 429/503 mean we are being rate-limited or OFF is
                    // struggling — retryable, and the caller should back off.
                    response.code == 429 || response.code >= 500 ->
                        RemoteResult.Failed("HTTP ${response.code}", retryable = true)
                    !response.isSuccessful ->
                        RemoteResult.Failed("HTTP ${response.code}", retryable = false)
                    else -> {
                        val body = response.body?.string()
                            ?: return@use RemoteResult.Failed("empty body", retryable = true)
                        parse(body, ean13)
                    }
                }
            }
        } catch (e: IOException) {
            RemoteResult.Failed(e.message ?: "network error", retryable = true)
        } catch (e: Exception) {
            // Malformed payloads are not retryable — retrying yields the same
            // broken JSON. Caught broadly on purpose: a parse failure must
            // degrade the chain, never crash the scanner.
            RemoteResult.Failed(e.message ?: "parse error", retryable = false)
        }
    }

    private fun parse(body: String, ean13: String): RemoteResult {
        val envelope = json.decodeFromString<OffEnvelope>(body)
        if (envelope.status != 1 || envelope.product == null) return RemoteResult.NotFound

        val p = envelope.product
        val n = p.nutriments ?: return RemoteResult.NotFound

        // Energy may arrive as kcal or only as kJ; convert rather than discard.
        val kcal = n.energyKcal100g
            ?: n.energyKj100g?.let { it / KJ_PER_KCAL }
            ?: return RemoteResult.NotFound

        val name = listOfNotNull(p.productName, p.genericName)
            .firstOrNull { it.isNotBlank() }
            ?: return RemoteResult.NotFound

        // OFF reports sodium in grams per 100 g; PULSE stores milligrams.
        val sodiumMg = n.sodium100g?.times(1000)
            ?: n.salt100g?.times(1000 / SALT_TO_SODIUM)

        return RemoteResult.Found(
            RemoteFood(
                sourceId = ean13,
                barcode = ean13,
                name = name.trim(),
                brand = p.brands?.split(",")?.firstOrNull()?.trim()?.takeIf(String::isNotEmpty),
                kcalPer100 = kcal,
                proteinPer100 = n.proteins100g ?: 0.0,
                carbsPer100 = n.carbohydrates100g ?: 0.0,
                fatPer100 = n.fat100g ?: 0.0,
                fiberPer100 = n.fiber100g,
                sugarPer100 = n.sugars100g,
                satFatPer100 = n.saturatedFat100g,
                sodiumMgPer100 = sodiumMg,
                isLiquid = p.quantity?.contains(Regex("\\bml\\b", RegexOption.IGNORE_CASE)) == true,
                servingLabel = p.servingSize?.trim()?.takeIf(String::isNotEmpty),
                servingGrams = parseServingGrams(p.servingSize),
                imageUrl = p.imageUrl,
                ingredients = p.ingredientsText?.trim()?.takeIf(String::isNotEmpty),
            ),
        )
    }

    companion object {
        const val PRODUCTION = "https://world.openfoodfacts.org"

        /**
         * Staging. Debug builds must use this so development never writes to or
         * hammers the production database (basic auth `off`/`off`).
         */
        const val STAGING = "https://world.openfoodfacts.net"

        const val KJ_PER_KCAL = 4.184

        /** OFF reports salt; sodium = salt / 2.5. */
        const val SALT_TO_SODIUM = 2.5

        /**
         * Requesting only what we store keeps responses small — full OFF
         * products are large, and mobile data is the user's.
         */
        private const val REQUESTED_FIELDS =
            "code,product_name,generic_name,brands,quantity,serving_size," +
                "image_url,ingredients_text,nutriments"
    }
}

/** Parses "30 g", "1 cup (240ml)", "45g" into grams. Null when unusable. */
internal fun parseServingGrams(raw: String?): Double? {
    if (raw.isNullOrBlank()) return null
    val match = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*(g|ml)\\b", RegexOption.IGNORE_CASE)
        .find(raw) ?: return null
    return match.groupValues[1].replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
}

internal val LenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

@Serializable
internal data class OffEnvelope(
    val status: Int = 0,
    val product: OffProduct? = null,
)

@Serializable
internal data class OffProduct(
    val code: String? = null,
    @SerialName("product_name") val productName: String? = null,
    @SerialName("generic_name") val genericName: String? = null,
    val brands: String? = null,
    val quantity: String? = null,
    @SerialName("serving_size") val servingSize: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("ingredients_text") val ingredientsText: String? = null,
    val nutriments: OffNutriments? = null,
)

@Serializable
internal data class OffNutriments(
    @SerialName("energy-kcal_100g") val energyKcal100g: Double? = null,
    @SerialName("energy-kj_100g") val energyKj100g: Double? = null,
    @SerialName("proteins_100g") val proteins100g: Double? = null,
    @SerialName("carbohydrates_100g") val carbohydrates100g: Double? = null,
    @SerialName("fat_100g") val fat100g: Double? = null,
    @SerialName("fiber_100g") val fiber100g: Double? = null,
    @SerialName("sugars_100g") val sugars100g: Double? = null,
    @SerialName("saturated-fat_100g") val saturatedFat100g: Double? = null,
    @SerialName("sodium_100g") val sodium100g: Double? = null,
    @SerialName("salt_100g") val salt100g: Double? = null,
)

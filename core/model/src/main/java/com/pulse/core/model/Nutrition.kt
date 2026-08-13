package com.pulse.core.model

/**
 * Nutrition facts for a concrete amount of food.
 *
 * All values are absolute (not per-100 g). The canonical per-100 g figures live
 * on [FoodNutrition]; this type is the *result* of scaling those to a real
 * portion, which is what gets snapshotted onto a diary entry.
 *
 * Units are fixed and metric (PHASE2_ARCHITECTURE.md §1.1): grams for macros,
 * milligrams for sodium/cholesterol/potassium, kilocalories for energy.
 */
data class Nutrition(
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double? = null,
    val sugarG: Double? = null,
    val satFatG: Double? = null,
    val sodiumMg: Double? = null,
    val cholesterolMg: Double? = null,
    val potassiumMg: Double? = null,
) {
    operator fun plus(other: Nutrition) = Nutrition(
        kcal = kcal + other.kcal,
        proteinG = proteinG + other.proteinG,
        carbsG = carbsG + other.carbsG,
        fatG = fatG + other.fatG,
        fiberG = fiberG addNullable other.fiberG,
        sugarG = sugarG addNullable other.sugarG,
        satFatG = satFatG addNullable other.satFatG,
        sodiumMg = sodiumMg addNullable other.sodiumMg,
        cholesterolMg = cholesterolMg addNullable other.cholesterolMg,
        potassiumMg = potassiumMg addNullable other.potassiumMg,
    )

    operator fun times(factor: Double) = Nutrition(
        kcal = kcal * factor,
        proteinG = proteinG * factor,
        carbsG = carbsG * factor,
        fatG = fatG * factor,
        fiberG = fiberG?.times(factor),
        sugarG = sugarG?.times(factor),
        satFatG = satFatG?.times(factor),
        sodiumMg = sodiumMg?.times(factor),
        cholesterolMg = cholesterolMg?.times(factor),
        potassiumMg = potassiumMg?.times(factor),
    )

    companion object {
        val ZERO = Nutrition(0.0, 0.0, 0.0, 0.0)

        fun sum(items: Iterable<Nutrition>): Nutrition =
            items.fold(ZERO) { acc, n -> acc + n }
    }
}

/**
 * Treat null as "unknown", not zero — but once any source reports a value the
 * running total should carry it. Summing an unknown with a known yields the
 * known value rather than discarding it.
 */
private infix fun Double?.addNullable(other: Double?): Double? = when {
    this == null && other == null -> null
    this == null -> other
    other == null -> this
    else -> this + other
}

/**
 * Canonical per-100 g (or per-100 ml for liquids) nutrition, as stored on `food`.
 * This mirrors the bundled dataset's native format, so import is lossless and
 * scaling is a single multiply (PHASE2_ARCHITECTURE.md §4.2).
 */
data class FoodNutrition(
    val kcalPer100: Double,
    val proteinPer100: Double,
    val carbsPer100: Double,
    val fatPer100: Double,
    val fiberPer100: Double? = null,
    val sugarPer100: Double? = null,
    val satFatPer100: Double? = null,
    val sodiumMgPer100: Double? = null,
    val cholesterolMgPer100: Double? = null,
    val potassiumMgPer100: Double? = null,
) {
    /** Scale to an absolute amount in grams (or millilitres for liquids). */
    fun forAmount(grams: Double): Nutrition {
        require(grams >= 0) { "amount must be non-negative, was $grams" }
        val f = grams / 100.0
        return Nutrition(
            kcal = kcalPer100 * f,
            proteinG = proteinPer100 * f,
            carbsG = carbsPer100 * f,
            fatG = fatPer100 * f,
            fiberG = fiberPer100?.times(f),
            sugarG = sugarPer100?.times(f),
            satFatG = satFatPer100?.times(f),
            sodiumMg = sodiumMgPer100?.times(f),
            cholesterolMg = cholesterolMgPer100?.times(f),
            potassiumMg = potassiumMgPer100?.times(f),
        )
    }

    /**
     * Scale by a serving and a quantity — the path every log action takes.
     * "2.5 × (1 slice = 45 g)" resolves to 112.5 g.
     */
    fun forServing(serving: Serving, quantity: Double): Nutrition {
        require(quantity >= 0) { "quantity must be non-negative, was $quantity" }
        return forAmount(serving.gramWeight * quantity)
    }
}

/**
 * A named portion of a food. `gramWeight` is grams, or millilitres when the food
 * is a liquid — the bundled dataset distinguishes these exactly via its serving
 * unit, so it is never inferred (DATASET_FINDINGS.md §3).
 */
data class Serving(
    val id: String,
    val label: String,
    val gramWeight: Double,
    val isDefault: Boolean = false,
) {
    init {
        require(gramWeight > 0) { "serving '$label' must have positive weight, was $gramWeight" }
    }
}

/**
 * How much a food's stated energy agrees with its macros, computed on ingest.
 * Calibrated against the full bundled dataset in DATASET_FINDINGS.md §5:
 * HIGH 81.1%, MEDIUM 11.9%, LOW 7.0%.
 */
enum class DataConfidence { HIGH, MEDIUM, LOW }

/** Atwater factors: protein and carbohydrate 4 kcal/g, fat 9 kcal/g. */
object EnergyCheck {
    const val KCAL_PER_G_PROTEIN = 4.0
    const val KCAL_PER_G_CARB = 4.0
    const val KCAL_PER_G_FAT = 9.0

    /** Physically impossible above ~900 kcal/100 g (pure fat). */
    const val MAX_PLAUSIBLE_KCAL_PER_100G = 900.0

    fun expectedKcal(proteinG: Double, carbsG: Double, fatG: Double): Double =
        proteinG * KCAL_PER_G_PROTEIN + carbsG * KCAL_PER_G_CARB + fatG * KCAL_PER_G_FAT

    fun classify(nutrition: FoodNutrition): DataConfidence = with(nutrition) {
        if (kcalPer100 > MAX_PLAUSIBLE_KCAL_PER_100G) return DataConfidence.LOW
        if (kcalPer100 <= 0.0) {
            val anyMacro = proteinPer100 > 0 || carbsPer100 > 0 || fatPer100 > 0
            return if (anyMacro) DataConfidence.MEDIUM else DataConfidence.LOW
        }
        val expected = expectedKcal(proteinPer100, carbsPer100, fatPer100)
        return when (val drift = kotlin.math.abs(expected - kcalPer100) / kcalPer100) {
            in 0.0..0.10 -> DataConfidence.HIGH
            in 0.10..0.25 -> DataConfidence.MEDIUM
            else -> if (drift.isNaN()) DataConfidence.LOW else DataConfidence.LOW
        }
    }
}

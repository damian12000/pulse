package com.pulse.core.domain

import kotlin.math.max
import kotlin.math.roundToInt

enum class Sex { MALE, FEMALE, UNSPECIFIED }

enum class ActivityLevel(val multiplier: Double, val label: String) {
    SEDENTARY(1.200, "Little or no exercise"),
    LIGHT(1.375, "Exercise 1–3 days/week"),
    MODERATE(1.550, "Exercise 3–5 days/week"),
    ACTIVE(1.725, "Exercise 6–7 days/week"),
    VERY_ACTIVE(1.900, "Hard exercise, physical job"),
}

enum class GoalType { LOSE, MAINTAIN, GAIN }

data class BodyProfile(
    val weightKg: Double,
    val heightCm: Double,
    val ageYears: Int,
    val sex: Sex,
    val activityLevel: ActivityLevel,
)

data class EnergyTarget(
    val bmr: Int,
    val tdee: Int,
    val target: Int,
    /** Signed daily delta from TDEE. */
    val adjustment: Int,
    /** True when the requested rate was clamped by a safety floor. */
    val wasFloored: Boolean,
)

/**
 * Basal and total energy expenditure, and the calorie target implied by a goal.
 *
 * Uses **Mifflin–St Jeor**, which is the current standard and more accurate than
 * Harris–Benedict for the general population.
 *
 * Every value here is an estimate with real individual variance — the app must
 * let the user override the result (Phase 6 requires manual overrides), and
 * these numbers should be presented as a starting point, not a prescription.
 */
object EnergyCalculator {

    /**
     * Energy in one kilogram of body mass. ~7,700 kcal is the conventional
     * figure for fat tissue and is what rate-of-change targets are based on.
     */
    const val KCAL_PER_KG = 7700.0

    /**
     * Safety floors. Sustained intake below these is not something an app
     * should silently recommend, so a requested deficit is clamped rather than
     * honoured blindly.
     */
    const val MIN_KCAL_MALE = 1500
    const val MIN_KCAL_FEMALE = 1200
    const val MIN_KCAL_UNSPECIFIED = 1200

    /** Mifflin–St Jeor. Sex is a formula term, nothing more. */
    fun bmr(profile: BodyProfile): Double = with(profile) {
        require(weightKg > 0) { "weight must be positive" }
        require(heightCm > 0) { "height must be positive" }
        require(ageYears > 0) { "age must be positive" }

        val base = 10.0 * weightKg + 6.25 * heightCm - 5.0 * ageYears
        when (sex) {
            Sex.MALE -> base + 5
            Sex.FEMALE -> base - 161
            // Midpoint when unspecified — avoids assuming, and the error is
            // smaller than the between-person variance of the formula itself.
            Sex.UNSPECIFIED -> base - 78
        }
    }

    fun tdee(profile: BodyProfile): Double = bmr(profile) * profile.activityLevel.multiplier

    /**
     * Daily calorie target for a goal.
     *
     * @param rateKgPerWeek desired rate of change; sign is taken from [goal],
     *   so callers may pass a positive magnitude.
     */
    fun target(
        profile: BodyProfile,
        goal: GoalType,
        rateKgPerWeek: Double = 0.0,
    ): EnergyTarget {
        val bmrValue = bmr(profile)
        val tdeeValue = tdee(profile)

        val magnitude = kotlin.math.abs(rateKgPerWeek)
        val dailyDelta = when (goal) {
            GoalType.MAINTAIN -> 0.0
            GoalType.LOSE -> -(magnitude * KCAL_PER_KG / 7.0)
            GoalType.GAIN -> +(magnitude * KCAL_PER_KG / 7.0)
        }

        val raw = tdeeValue + dailyDelta
        val floor = minimumIntake(profile.sex).toDouble()
        val clamped = max(raw, floor)

        return EnergyTarget(
            bmr = bmrValue.roundToInt(),
            tdee = tdeeValue.roundToInt(),
            target = clamped.roundToInt(),
            adjustment = (clamped - tdeeValue).roundToInt(),
            wasFloored = clamped > raw,
        )
    }

    fun minimumIntake(sex: Sex): Int = when (sex) {
        Sex.MALE -> MIN_KCAL_MALE
        Sex.FEMALE -> MIN_KCAL_FEMALE
        Sex.UNSPECIFIED -> MIN_KCAL_UNSPECIFIED
    }

    /**
     * A rate that is sensible for the person's size — roughly 0.5–1.0% of body
     * mass per week. Used to pre-fill onboarding rather than to constrain.
     */
    fun suggestedRateKgPerWeek(weightKg: Double, goal: GoalType): Double = when (goal) {
        GoalType.MAINTAIN -> 0.0
        GoalType.LOSE -> (weightKg * 0.0075).coerceIn(0.25, 1.0)
        GoalType.GAIN -> (weightKg * 0.0035).coerceIn(0.10, 0.5)
    }
}

data class MacroTargets(
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
) {
    val kcal: Int get() = proteinG * 4 + carbsG * 4 + fatG * 9
}

/**
 * Macro split from a calorie target.
 *
 * Protein and fat are set per kilogram of body mass — that is how the evidence
 * is actually expressed — and carbohydrate takes the remainder. A pure
 * percentage split gives absurd protein at very low or very high calories.
 */
object MacroCalculator {

    const val KCAL_PER_G_PROTEIN = 4
    const val KCAL_PER_G_CARB = 4
    const val KCAL_PER_G_FAT = 9

    /** Higher protein while cutting helps preserve lean mass in a deficit. */
    fun proteinPerKg(goal: GoalType): Double = when (goal) {
        GoalType.LOSE -> 2.0
        GoalType.MAINTAIN -> 1.8
        GoalType.GAIN -> 1.8
    }

    /** Below ~0.6 g/kg fat intake starts to affect hormonal function. */
    fun fatPerKg(goal: GoalType): Double = when (goal) {
        GoalType.LOSE -> 0.8
        GoalType.MAINTAIN -> 0.9
        GoalType.GAIN -> 1.0
    }

    fun calculate(calorieTarget: Int, weightKg: Double, goal: GoalType): MacroTargets {
        require(calorieTarget > 0) { "calorie target must be positive" }
        require(weightKg > 0) { "weight must be positive" }

        val protein = (weightKg * proteinPerKg(goal)).roundToInt()
        val fat = (weightKg * fatPerKg(goal)).roundToInt()

        val remaining = calorieTarget - protein * KCAL_PER_G_PROTEIN - fat * KCAL_PER_G_FAT

        // At a very aggressive deficit, protein + fat can exceed the whole
        // budget. Rather than emit negative carbs, scale both back
        // proportionally and leave a small carb floor.
        if (remaining < 0) {
            val available = calorieTarget * 0.90
            val proteinKcal = protein * KCAL_PER_G_PROTEIN
            val fatKcal = fat * KCAL_PER_G_FAT
            val scale = available / (proteinKcal + fatKcal)
            val scaledProtein = (protein * scale).roundToInt()
            val scaledFat = (fat * scale).roundToInt()
            val carbs = (calorieTarget
                - scaledProtein * KCAL_PER_G_PROTEIN
                - scaledFat * KCAL_PER_G_FAT) / KCAL_PER_G_CARB
            return MacroTargets(scaledProtein, max(carbs, 0), scaledFat)
        }

        return MacroTargets(protein, remaining / KCAL_PER_G_CARB, fat)
    }
}

/**
 * Daily water target.
 *
 * 35 ml/kg is a common baseline; activity adds to it. Deliberately simple —
 * hydration needs vary hugely and the user can override.
 */
object WaterCalculator {
    const val ML_PER_KG = 35.0

    fun dailyTargetMl(weightKg: Double, activityLevel: ActivityLevel): Int {
        require(weightKg > 0) { "weight must be positive" }
        val base = weightKg * ML_PER_KG
        val activityBonus = when (activityLevel) {
            ActivityLevel.SEDENTARY -> 0.0
            ActivityLevel.LIGHT -> 250.0
            ActivityLevel.MODERATE -> 500.0
            ActivityLevel.ACTIVE -> 750.0
            ActivityLevel.VERY_ACTIVE -> 1000.0
        }
        // Round to the nearest 50 ml — false precision helps nobody.
        return (((base + activityBonus) / 50).roundToInt() * 50)
    }
}

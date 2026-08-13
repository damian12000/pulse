package com.pulse.core.domain

enum class Meal { BREAKFAST, LUNCH, DINNER, SNACK }

/**
 * Picks the meal a log entry most likely belongs to, from the time of day.
 *
 * This exists purely for speed: pre-selecting the right meal removes a tap from
 * the most common action in the app. It is always a *default* the user can
 * override — never an assumption applied silently.
 *
 * Boundaries are deliberately generous and the gaps fall to SNACK, which is
 * both the safest wrong answer and usually the right one at odd hours.
 */
object MealTiming {

    // Local wall-clock hours.
    const val BREAKFAST_START = 4
    const val BREAKFAST_END = 11
    const val LUNCH_START = 11
    const val LUNCH_END = 15
    const val DINNER_START = 17
    const val DINNER_END = 22

    fun forHour(hourOfDay: Int): Meal {
        require(hourOfDay in 0..23) { "hour must be 0..23, was $hourOfDay" }
        return when (hourOfDay) {
            in BREAKFAST_START until BREAKFAST_END -> Meal.BREAKFAST
            in LUNCH_START until LUNCH_END -> Meal.LUNCH
            in DINNER_START until DINNER_END -> Meal.DINNER
            // Mid-afternoon (15–17) and late night (22–04) are genuinely
            // ambiguous; SNACK is the honest answer rather than rounding to
            // whichever meal is nearer.
            else -> Meal.SNACK
        }
    }
}

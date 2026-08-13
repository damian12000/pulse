package com.pulse.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MealTimingTest {

    @Test
    fun `morning is breakfast`() {
        assertEquals(Meal.BREAKFAST, MealTiming.forHour(7))
        assertEquals(Meal.BREAKFAST, MealTiming.forHour(4))
        assertEquals(Meal.BREAKFAST, MealTiming.forHour(10))
    }

    @Test
    fun `midday is lunch`() {
        assertEquals(Meal.LUNCH, MealTiming.forHour(11))
        assertEquals(Meal.LUNCH, MealTiming.forHour(13))
        assertEquals(Meal.LUNCH, MealTiming.forHour(14))
    }

    @Test
    fun `evening is dinner`() {
        assertEquals(Meal.DINNER, MealTiming.forHour(17))
        assertEquals(Meal.DINNER, MealTiming.forHour(19))
        assertEquals(Meal.DINNER, MealTiming.forHour(21))
    }

    /**
     * Mid-afternoon and late night are genuinely ambiguous. Snack is the honest
     * answer — rounding to the nearer meal would be a confident wrong guess the
     * user has to undo.
     */
    @Test
    fun `ambiguous hours fall to snack rather than guessing`() {
        assertEquals(Meal.SNACK, MealTiming.forHour(15))
        assertEquals(Meal.SNACK, MealTiming.forHour(16))
        assertEquals(Meal.SNACK, MealTiming.forHour(22))
        assertEquals(Meal.SNACK, MealTiming.forHour(23))
        assertEquals(Meal.SNACK, MealTiming.forHour(0))
        assertEquals(Meal.SNACK, MealTiming.forHour(3))
    }

    @Test
    fun `every hour of the day maps to some meal`() {
        val covered = (0..23).map { MealTiming.forHour(it) }
        assertEquals(24, covered.size)
    }

    @Test
    fun `an impossible hour is rejected rather than silently wrapped`() {
        assertFailsWith<IllegalArgumentException> { MealTiming.forHour(24) }
        assertFailsWith<IllegalArgumentException> { MealTiming.forHour(-1) }
    }
}

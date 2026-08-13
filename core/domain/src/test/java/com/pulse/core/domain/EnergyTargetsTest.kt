package com.pulse.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnergyCalculatorTest {

    private val male = BodyProfile(
        weightKg = 80.0, heightCm = 180.0, ageYears = 30,
        sex = Sex.MALE, activityLevel = ActivityLevel.MODERATE,
    )
    private val female = BodyProfile(
        weightKg = 65.0, heightCm = 165.0, ageYears = 30,
        sex = Sex.FEMALE, activityLevel = ActivityLevel.MODERATE,
    )

    @Test
    fun `Mifflin-St Jeor matches the published formula for men`() {
        // 10(80) + 6.25(180) - 5(30) + 5 = 800 + 1125 - 150 + 5 = 1780
        assertEquals(1780.0, EnergyCalculator.bmr(male), 1e-9)
    }

    @Test
    fun `Mifflin-St Jeor matches the published formula for women`() {
        // 10(65) + 6.25(165) - 5(30) - 161 = 650 + 1031.25 - 150 - 161 = 1370.25
        assertEquals(1370.25, EnergyCalculator.bmr(female), 1e-9)
    }

    @Test
    fun `unspecified sex sits between the male and female results`() {
        val unspecified = male.copy(sex = Sex.UNSPECIFIED)
        val bmr = EnergyCalculator.bmr(unspecified)
        assertTrue(bmr < EnergyCalculator.bmr(male))
        assertTrue(bmr > EnergyCalculator.bmr(male.copy(sex = Sex.FEMALE)))
    }

    @Test
    fun `tdee applies the activity multiplier`() {
        assertEquals(1780.0 * 1.55, EnergyCalculator.tdee(male), 1e-9)
        assertEquals(1780.0 * 1.2, EnergyCalculator.tdee(male.copy(activityLevel = ActivityLevel.SEDENTARY)), 1e-9)
    }

    @Test
    fun `maintain target equals tdee with no adjustment`() {
        val t = EnergyCalculator.target(male, GoalType.MAINTAIN)
        assertEquals(t.tdee, t.target)
        assertEquals(0, t.adjustment)
        assertTrue(!t.wasFloored)
    }

    @Test
    fun `losing half a kilo a week is a deficit of about 550 kcal`() {
        val t = EnergyCalculator.target(male, GoalType.LOSE, rateKgPerWeek = 0.5)
        // 0.5 * 7700 / 7 = 550
        assertEquals(-550, t.adjustment)
        assertEquals(t.tdee - 550, t.target)
    }

    @Test
    fun `gaining adds calories`() {
        val t = EnergyCalculator.target(male, GoalType.GAIN, rateKgPerWeek = 0.25)
        assertEquals(275, t.adjustment)
        assertTrue(t.target > t.tdee)
    }

    @Test
    fun `rate sign is taken from the goal, not the caller`() {
        val fromPositive = EnergyCalculator.target(male, GoalType.LOSE, rateKgPerWeek = 0.5)
        val fromNegative = EnergyCalculator.target(male, GoalType.LOSE, rateKgPerWeek = -0.5)
        assertEquals(fromPositive.target, fromNegative.target)
    }

    @Test
    fun `an absurd deficit is clamped to the safety floor and flagged`() {
        // 3 kg/week would demand a deficit far below any safe intake
        val t = EnergyCalculator.target(female, GoalType.LOSE, rateKgPerWeek = 3.0)
        assertEquals(EnergyCalculator.MIN_KCAL_FEMALE, t.target)
        assertTrue(t.wasFloored, "an unsafe target must be reported as floored, not silently applied")
    }

    @Test
    fun `a reasonable deficit is not floored`() {
        val t = EnergyCalculator.target(male, GoalType.LOSE, rateKgPerWeek = 0.5)
        assertTrue(!t.wasFloored)
    }

    @Test
    fun `invalid body measurements are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            EnergyCalculator.bmr(male.copy(weightKg = 0.0))
        }
        assertFailsWith<IllegalArgumentException> {
            EnergyCalculator.bmr(male.copy(heightCm = -1.0))
        }
        assertFailsWith<IllegalArgumentException> {
            EnergyCalculator.bmr(male.copy(ageYears = 0))
        }
    }

    @Test
    fun `suggested rate scales with bodyweight and stays in sane bounds`() {
        assertEquals(0.0, EnergyCalculator.suggestedRateKgPerWeek(80.0, GoalType.MAINTAIN), 1e-9)

        val heavy = EnergyCalculator.suggestedRateKgPerWeek(120.0, GoalType.LOSE)
        val light = EnergyCalculator.suggestedRateKgPerWeek(55.0, GoalType.LOSE)
        assertTrue(heavy > light)
        assertTrue(heavy <= 1.0)
        assertTrue(light >= 0.25)
    }
}

class MacroCalculatorTest {

    @Test
    fun `macros add up to the calorie target`() {
        val m = MacroCalculator.calculate(2500, weightKg = 80.0, goal = GoalType.MAINTAIN)
        // integer rounding means within a few kcal, not exact
        assertTrue(kotlin.math.abs(m.kcal - 2500) <= 4, "got ${m.kcal}")
    }

    @Test
    fun `protein and fat are set per kilogram of bodyweight`() {
        val m = MacroCalculator.calculate(2500, weightKg = 80.0, goal = GoalType.MAINTAIN)
        assertEquals((80 * 1.8).toInt(), m.proteinG)
        assertEquals((80 * 0.9).toInt(), m.fatG)
    }

    @Test
    fun `cutting raises protein and lowers fat`() {
        val cut = MacroCalculator.calculate(2000, 80.0, GoalType.LOSE)
        val maintain = MacroCalculator.calculate(2000, 80.0, GoalType.MAINTAIN)
        assertTrue(cut.proteinG > maintain.proteinG)
        assertTrue(cut.fatG < maintain.fatG)
    }

    @Test
    fun `carbs never go negative at an aggressive deficit`() {
        // 1200 kcal for a 100 kg person: protein+fat alone would exceed budget
        val m = MacroCalculator.calculate(1200, weightKg = 100.0, goal = GoalType.LOSE)
        assertTrue(m.carbsG >= 0, "carbs were ${m.carbsG}")
        assertTrue(m.proteinG > 0)
        assertTrue(m.fatG > 0)
        assertTrue(m.kcal <= 1200 + 10)
    }

    @Test
    fun `invalid inputs are rejected`() {
        assertFailsWith<IllegalArgumentException> { MacroCalculator.calculate(0, 80.0, GoalType.LOSE) }
        assertFailsWith<IllegalArgumentException> { MacroCalculator.calculate(2000, 0.0, GoalType.LOSE) }
    }
}

class WaterCalculatorTest {

    @Test
    fun `base target is 35 ml per kilogram, rounded to 50`() {
        // 70 * 35 = 2450, sedentary adds nothing
        assertEquals(2450, WaterCalculator.dailyTargetMl(70.0, ActivityLevel.SEDENTARY))
    }

    @Test
    fun `activity increases the target`() {
        val sedentary = WaterCalculator.dailyTargetMl(70.0, ActivityLevel.SEDENTARY)
        val veryActive = WaterCalculator.dailyTargetMl(70.0, ActivityLevel.VERY_ACTIVE)
        assertEquals(sedentary + 1000, veryActive)
    }

    @Test
    fun `target is always a round 50 ml`() {
        for (kg in listOf(52.3, 61.7, 78.9, 95.1)) {
            for (level in ActivityLevel.entries) {
                assertEquals(0, WaterCalculator.dailyTargetMl(kg, level) % 50)
            }
        }
    }

    @Test
    fun `invalid weight is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            WaterCalculator.dailyTargetMl(0.0, ActivityLevel.MODERATE)
        }
    }
}

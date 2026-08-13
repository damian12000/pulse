package com.pulse.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Encodes the non-negotiable invariants from PHASE2_ARCHITECTURE.md §10.
 * The headline one is linear scaling: 1 serving = 200 kcal => 2.5 = 500 kcal,
 * with every macro in proportion.
 */
class NutritionTest {

    private val eps = 1e-9

    /** A food where one serving is deliberately exactly 200 kcal. */
    private val food = FoodNutrition(
        kcalPer100 = 400.0,
        proteinPer100 = 20.0,
        carbsPer100 = 40.0,
        fatPer100 = 16.0,
        fiberPer100 = 6.0,
        sodiumMgPer100 = 300.0,
    )
    private val serving = Serving(id = "s1", label = "1 serving", gramWeight = 50.0, isDefault = true)

    @Test
    fun `one serving scales from per-100g correctly`() {
        val n = food.forServing(serving, 1.0)
        assertEquals(200.0, n.kcal, eps)
        assertEquals(10.0, n.proteinG, eps)
        assertEquals(20.0, n.carbsG, eps)
        assertEquals(8.0, n.fatG, eps)
        assertEquals(3.0, n.fiberG!!, eps)
        assertEquals(150.0, n.sodiumMg!!, eps)
    }

    @Test
    fun `nutrition scales linearly - the brief's worked example`() {
        val one = food.forServing(serving, 1.0)
        val twoAndHalf = food.forServing(serving, 2.5)

        assertEquals(200.0, one.kcal, eps)
        assertEquals(500.0, twoAndHalf.kcal, eps)

        // every macro in proportion, not just calories
        assertEquals(one.proteinG * 2.5, twoAndHalf.proteinG, eps)
        assertEquals(one.carbsG * 2.5, twoAndHalf.carbsG, eps)
        assertEquals(one.fatG * 2.5, twoAndHalf.fatG, eps)
        assertEquals(one.fiberG!! * 2.5, twoAndHalf.fiberG!!, eps)
        assertEquals(one.sodiumMg!! * 2.5, twoAndHalf.sodiumMg!!, eps)
    }

    @Test
    fun `fractional and zero quantities behave`() {
        assertEquals(100.0, food.forServing(serving, 0.5).kcal, eps)
        assertEquals(0.0, food.forServing(serving, 0.0).kcal, eps)
    }

    @Test
    fun `negative quantity is rejected rather than silently logged`() {
        assertFailsWith<IllegalArgumentException> { food.forServing(serving, -1.0) }
        assertFailsWith<IllegalArgumentException> { food.forAmount(-5.0) }
    }

    @Test
    fun `serving must have positive weight`() {
        assertFailsWith<IllegalArgumentException> { Serving("x", "bad", 0.0) }
        assertFailsWith<IllegalArgumentException> { Serving("x", "bad", -10.0) }
    }

    @Test
    fun `arbitrary gram amount scales`() {
        val n = food.forAmount(37.0)
        assertEquals(400.0 * 0.37, n.kcal, eps)
        assertEquals(20.0 * 0.37, n.proteinG, eps)
    }

    @Test
    fun `daily total is the sum of entries`() {
        val entries = listOf(
            food.forServing(serving, 1.0),
            food.forServing(serving, 2.0),
            food.forAmount(100.0),
        )
        val total = Nutrition.sum(entries)
        assertEquals(200.0 + 400.0 + 400.0, total.kcal, eps)
        assertEquals(10.0 + 20.0 + 20.0, total.proteinG, eps)
    }

    @Test
    fun `summing preserves a known value when the other side is unknown`() {
        val known = Nutrition(100.0, 5.0, 10.0, 2.0, fiberG = 3.0)
        val unknown = Nutrition(100.0, 5.0, 10.0, 2.0, fiberG = null)

        // unknown must not zero out a known figure
        assertEquals(3.0, (known + unknown).fiberG!!, eps)
        assertEquals(3.0, (unknown + known).fiberG!!, eps)
        // and two unknowns stay unknown rather than becoming a misleading 0
        assertNull((unknown + unknown).fiberG)
    }

    @Test
    fun `empty day totals zero`() {
        assertEquals(0.0, Nutrition.sum(emptyList()).kcal, eps)
    }
}

class EnergyCheckTest {

    @Test
    fun `consistent macros are HIGH confidence`() {
        // 20p + 40c + 16f = 80 + 160 + 144 = 384 vs stated 400 -> 4% drift
        val n = FoodNutrition(400.0, 20.0, 40.0, 16.0)
        assertEquals(DataConfidence.HIGH, EnergyCheck.classify(n))
    }

    @Test
    fun `moderately inconsistent macros are MEDIUM`() {
        // expected 384 vs stated 320 -> 20% drift
        val n = FoodNutrition(320.0, 20.0, 40.0, 16.0)
        assertEquals(DataConfidence.MEDIUM, EnergyCheck.classify(n))
    }

    @Test
    fun `wildly inconsistent macros are LOW`() {
        // expected 384 vs stated 100 -> 284% drift
        val n = FoodNutrition(100.0, 20.0, 40.0, 16.0)
        assertEquals(DataConfidence.LOW, EnergyCheck.classify(n))
    }

    @Test
    fun `physically impossible energy is LOW regardless of macro agreement`() {
        // internally consistent but >900 kcal/100g is impossible
        val n = FoodNutrition(1000.0, 0.0, 0.0, 111.1)
        assertEquals(DataConfidence.LOW, EnergyCheck.classify(n))
    }

    @Test
    fun `zero calorie foods with no macros are LOW, with macros are MEDIUM`() {
        assertEquals(DataConfidence.LOW, EnergyCheck.classify(FoodNutrition(0.0, 0.0, 0.0, 0.0)))
        assertEquals(DataConfidence.MEDIUM, EnergyCheck.classify(FoodNutrition(0.0, 5.0, 0.0, 0.0)))
    }

    @Test
    fun `water is a real zero-calorie food and does not crash`() {
        assertEquals(DataConfidence.LOW, EnergyCheck.classify(FoodNutrition(0.0, 0.0, 0.0, 0.0)))
    }
}

class UnitConverterTest {

    private val eps = 1e-6

    @Test
    fun `mass round-trips without drift`() {
        val kg = 82.5
        assertEquals(kg, UnitConverter.lbToKg(UnitConverter.kgToLb(kg)), eps)
    }

    @Test
    fun `known mass conversions`() {
        assertEquals(220.462262, UnitConverter.kgToLb(100.0), 1e-4)
        assertEquals(45.359237, UnitConverter.lbToKg(100.0), 1e-4)
    }

    @Test
    fun `length round-trips and feet-inches decompose correctly`() {
        assertEquals(180.0, UnitConverter.inchesToCm(UnitConverter.cmToInches(180.0)), eps)

        val cm = UnitConverter.feetInchesToCm(5, 11.0)
        assertEquals(180.34, cm, 1e-2)

        val (feet, inches) = UnitConverter.cmToFeetInches(cm)
        assertEquals(5, feet)
        assertEquals(11.0, inches, 1e-6)
    }

    @Test
    fun `volume round-trips`() {
        assertEquals(500.0, UnitConverter.flOzToMl(UnitConverter.mlToFlOz(500.0)), eps)
    }

    @Test
    fun `unit-aware helpers agree with direct conversion`() {
        assertEquals(82.5, UnitConverter.toKg(UnitConverter.fromKg(82.5, MassUnit.POUNDS), MassUnit.POUNDS), eps)
        assertEquals(82.5, UnitConverter.fromKg(82.5, MassUnit.KILOGRAMS), eps)
    }
}

class BarcodeNormalizerTest {

    @Test
    fun `EAN-13 passes through unchanged`() {
        assertEquals("0013764027053", BarcodeNormalizer.toEan13("0013764027053"))
    }

    @Test
    fun `UPC-A is zero-padded to EAN-13`() {
        // This is the classic silent 'product not found' bug.
        assertEquals("0078742040370", BarcodeNormalizer.toEan13("078742040370"))
    }

    @Test
    fun `UPC-E expands to UPC-A then EAN-13`() {
        // 01278906 -> 012000007896 -> 0012000007896
        val expanded = BarcodeNormalizer.expandUpcE("01278906")
        assertEquals("012000007896", expanded)
        assertEquals("0012000007896", BarcodeNormalizer.toEan13("01278906"))
    }

    @Test
    fun `UPC-E expansion covers every last-digit branch`() {
        // last digit 0/1/2 branch
        assertEquals(12, BarcodeNormalizer.expandUpcE("01234505")?.length)
        // last digit 3 branch
        assertEquals(12, BarcodeNormalizer.expandUpcE("01234535")?.length)
        // last digit 4 branch
        assertEquals(12, BarcodeNormalizer.expandUpcE("01234545")?.length)
        // last digit 5-9 branch
        assertEquals(12, BarcodeNormalizer.expandUpcE("01234595")?.length)
    }

    @Test
    fun `GTIN-14 with leading zero reduces to EAN-13`() {
        assertEquals("0013764027053", BarcodeNormalizer.toEan13("00013764027053"))
    }

    @Test
    fun `non-digits are stripped before normalizing`() {
        assertEquals("0078742040370", BarcodeNormalizer.toEan13(" 0787-4204 0370 "))
    }

    @Test
    fun `unusable input returns null rather than a bogus code`() {
        assertNull(BarcodeNormalizer.toEan13(null))
        assertNull(BarcodeNormalizer.toEan13(""))
        assertNull(BarcodeNormalizer.toEan13("12345"))
        assertNull(BarcodeNormalizer.toEan13("abcdef"))
    }

    @Test
    fun `check digit validation accepts real barcodes and rejects corrupted ones`() {
        assertTrue(BarcodeNormalizer.isValidCheckDigit("0013764027053"))
        assertTrue(BarcodeNormalizer.isValidCheckDigit("078742040370"))
        // flip the check digit
        assertTrue(!BarcodeNormalizer.isValidCheckDigit("0013764027054"))
    }
}

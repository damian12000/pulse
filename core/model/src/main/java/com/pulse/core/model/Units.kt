package com.pulse.core.model

import kotlin.math.roundToInt

/**
 * Unit handling for PULSE.
 *
 * **Storage is always metric** (PHASE2_ARCHITECTURE.md §1.1): kilograms, metres,
 * millilitres, grams, kilocalories, seconds. Imperial exists only as a display
 * and input conversion at the UI boundary. Changing unit preference must never
 * rewrite stored data.
 */
enum class MassUnit { KILOGRAMS, POUNDS }
enum class LengthUnit { CENTIMETRES, INCHES }
enum class VolumeUnit { MILLILITRES, FLUID_OUNCES }

object UnitConverter {
    const val LB_PER_KG = 2.20462262185
    const val CM_PER_INCH = 2.54
    const val ML_PER_FL_OZ_US = 29.5735295625

    // --- mass ---------------------------------------------------------------
    fun kgToLb(kg: Double) = kg * LB_PER_KG
    fun lbToKg(lb: Double) = lb / LB_PER_KG

    fun fromKg(kg: Double, unit: MassUnit) = when (unit) {
        MassUnit.KILOGRAMS -> kg
        MassUnit.POUNDS -> kgToLb(kg)
    }

    fun toKg(value: Double, unit: MassUnit) = when (unit) {
        MassUnit.KILOGRAMS -> value
        MassUnit.POUNDS -> lbToKg(value)
    }

    // --- length -------------------------------------------------------------
    fun cmToInches(cm: Double) = cm / CM_PER_INCH
    fun inchesToCm(inches: Double) = inches * CM_PER_INCH

    fun fromCm(cm: Double, unit: LengthUnit) = when (unit) {
        LengthUnit.CENTIMETRES -> cm
        LengthUnit.INCHES -> cmToInches(cm)
    }

    fun toCm(value: Double, unit: LengthUnit) = when (unit) {
        LengthUnit.CENTIMETRES -> value
        LengthUnit.INCHES -> inchesToCm(value)
    }

    /** Height entered as feet + inches, stored as centimetres. */
    fun feetInchesToCm(feet: Int, inches: Double) = inchesToCm(feet * 12 + inches)

    /** Centimetres back to whole feet plus remaining inches, for display. */
    fun cmToFeetInches(cm: Double): Pair<Int, Double> {
        val totalInches = cmToInches(cm)
        val feet = (totalInches / 12).toInt()
        return feet to (totalInches - feet * 12)
    }

    // --- volume -------------------------------------------------------------
    fun mlToFlOz(ml: Double) = ml / ML_PER_FL_OZ_US
    fun flOzToMl(flOz: Double) = flOz * ML_PER_FL_OZ_US

    fun fromMl(ml: Double, unit: VolumeUnit) = when (unit) {
        VolumeUnit.MILLILITRES -> ml
        VolumeUnit.FLUID_OUNCES -> mlToFlOz(ml)
    }

    fun toMl(value: Double, unit: VolumeUnit) = when (unit) {
        VolumeUnit.MILLILITRES -> value
        VolumeUnit.FLUID_OUNCES -> flOzToMl(value)
    }

    // --- distance -----------------------------------------------------------
    const val METRES_PER_MILE = 1609.344
    fun metresToKm(m: Double) = m / 1000.0
    fun metresToMiles(m: Double) = m / METRES_PER_MILE
}

/**
 * Barcode normalization.
 *
 * The bundled dataset and Open Food Facts both key on EAN-13, but scanners
 * return UPC-E and UPC-A too. Getting this wrong is a silent "product not
 * found" for products we actually have — see PHASE2_ARCHITECTURE.md §6.3.
 */
object BarcodeNormalizer {

    /** Normalize any supported retail barcode to its EAN-13 form, or null if unusable. */
    fun toEan13(raw: String?): String? {
        val digits = raw?.filter(Char::isDigit) ?: return null
        return when (digits.length) {
            13 -> digits
            12 -> "0$digits"                      // UPC-A -> EAN-13
            8 -> expandUpcE(digits)?.let { "0$it" } // UPC-E -> UPC-A -> EAN-13
            14 -> if (digits.startsWith("0")) digits.substring(1) else null // GTIN-14
            else -> null
        }
    }

    /**
     * Expand a zero-suppressed 8-digit UPC-E to its 12-digit UPC-A form.
     * Input must be `[number system][6 payload digits][check digit]`.
     */
    fun expandUpcE(upcE: String): String? {
        if (upcE.length != 8 || !upcE.all(Char::isDigit)) return null
        val system = upcE[0]
        if (system != '0' && system != '1') return null

        val d = upcE.substring(1, 7)
        val check = upcE[7]
        val manufacturer: String
        val product: String

        when (d[5]) {
            '0', '1', '2' -> {
                manufacturer = "${d[0]}${d[1]}${d[5]}00"
                product = "00${d[2]}${d[3]}${d[4]}"
            }
            '3' -> {
                manufacturer = "${d[0]}${d[1]}${d[2]}00"
                product = "000${d[3]}${d[4]}"
            }
            '4' -> {
                manufacturer = "${d[0]}${d[1]}${d[2]}${d[3]}0"
                product = "0000${d[4]}"
            }
            else -> {
                manufacturer = "${d[0]}${d[1]}${d[2]}${d[3]}${d[4]}"
                product = "0000${d[5]}"
            }
        }
        return "$system$manufacturer$product$check"
    }

    /** Validate a GTIN-8/12/13/14 modulo-10 check digit. */
    fun isValidCheckDigit(code: String): Boolean {
        if (code.length !in setOf(8, 12, 13, 14) || !code.all(Char::isDigit)) return false
        val body = code.dropLast(1)
        val expected = code.last().digitToInt()
        // Weights alternate 3/1 from the rightmost body digit leftwards.
        val sum = body.reversed()
            .mapIndexed { i, ch -> ch.digitToInt() * if (i % 2 == 0) 3 else 1 }
            .sum()
        return (10 - sum % 10) % 10 == expected
    }
}

/** Rounding helpers so displayed macros don't show spurious precision. */
object Display {
    fun kcal(value: Double): Int = value.roundToInt()
    fun grams(value: Double): Double = (value * 10).roundToInt() / 10.0
    fun milligrams(value: Double): Int = value.roundToInt()
}

package com.pulse.core.domain

import kotlin.math.roundToInt

/**
 * How a given exercise is logged. Mirrors the `trackingMode` column and is what
 * lets one workout engine cover strength, cardio and mobility rather than a
 * screen per modality.
 */
enum class TrackingMode {
    WEIGHT_REPS,
    REPS_ONLY,
    WEIGHTED_BODYWEIGHT,
    ASSISTED_BODYWEIGHT,
    DURATION,
    DURATION_WEIGHT,
    DISTANCE_DURATION;

    val usesWeight: Boolean
        get() = this in setOf(WEIGHT_REPS, WEIGHTED_BODYWEIGHT, ASSISTED_BODYWEIGHT, DURATION_WEIGHT)

    val usesReps: Boolean
        get() = this in setOf(WEIGHT_REPS, REPS_ONLY, WEIGHTED_BODYWEIGHT, ASSISTED_BODYWEIGHT)

    val usesDuration: Boolean
        get() = this in setOf(DURATION, DURATION_WEIGHT, DISTANCE_DURATION)

    val usesDistance: Boolean
        get() = this == DISTANCE_DURATION
}

/** One logged set, in whichever dimensions its exercise actually uses. */
data class SetPerformance(
    val weightKg: Double? = null,
    val reps: Int? = null,
    val durationSec: Int? = null,
    val distanceM: Double? = null,
    val bodyweightKg: Double? = null,
)

/**
 * Estimated one-rep max.
 *
 * Every formula here is an *estimate* and they diverge sharply at high rep
 * counts — which is why estimates above [MAX_RELIABLE_REPS] are refused rather
 * than reported with false confidence.
 */
object OneRepMax {

    /** Beyond ~12 reps the formulas disagree by enough to be misleading. */
    const val MAX_RELIABLE_REPS = 12

    /** Epley: w × (1 + reps/30). Tends to read slightly high at high reps. */
    fun epley(weightKg: Double, reps: Int): Double {
        require(weightKg > 0) { "weight must be positive" }
        require(reps > 0) { "reps must be positive" }
        return if (reps == 1) weightKg else weightKg * (1 + reps / 30.0)
    }

    /** Brzycki: w × 36/(37 − reps). Tends to read slightly low at high reps. */
    fun brzycki(weightKg: Double, reps: Int): Double {
        require(weightKg > 0) { "weight must be positive" }
        require(reps in 1..36) { "Brzycki is undefined at $reps reps" }
        return if (reps == 1) weightKg else weightKg * 36.0 / (37.0 - reps)
    }

    /**
     * Mean of Epley and Brzycki — they err in opposite directions, so averaging
     * is more stable than either alone.
     *
     * Returns null when the set is outside the range where an estimate means
     * anything.
     */
    fun estimate(weightKg: Double, reps: Int): Double? {
        if (weightKg <= 0 || reps <= 0) return null
        if (reps > MAX_RELIABLE_REPS) return null
        return (epley(weightKg, reps) + brzycki(weightKg, reps)) / 2.0
    }
}

object Volume {
    /**
     * Set volume = weight × reps. Only meaningful for loaded rep work; a plank
     * or a run has no volume in this sense, and returning 0 rather than a
     * fabricated number keeps totals honest.
     */
    fun forSet(mode: TrackingMode, set: SetPerformance): Double {
        if (!mode.usesWeight || !mode.usesReps) return 0.0
        val reps = set.reps ?: return 0.0
        val load = when (mode) {
            // A weighted dip moves bodyweight plus the added load.
            TrackingMode.WEIGHTED_BODYWEIGHT ->
                (set.bodyweightKg ?: 0.0) + (set.weightKg ?: 0.0)
            // Assistance subtracts from bodyweight.
            TrackingMode.ASSISTED_BODYWEIGHT ->
                ((set.bodyweightKg ?: 0.0) - (set.weightKg ?: 0.0)).coerceAtLeast(0.0)
            else -> set.weightKg ?: return 0.0
        }
        return load * reps
    }

    fun total(mode: TrackingMode, sets: List<SetPerformance>): Double =
        sets.sumOf { forSet(mode, it) }
}

object Pace {
    /** Seconds per kilometre. Null when either input is missing or zero. */
    fun secondsPerKm(distanceM: Double?, durationSec: Int?): Double? {
        if (distanceM == null || durationSec == null) return null
        if (distanceM <= 0 || durationSec <= 0) return null
        return durationSec / (distanceM / 1000.0)
    }

    /** Formats seconds-per-km as m:ss. */
    fun format(secondsPerKm: Double?): String? {
        if (secondsPerKm == null || !secondsPerKm.isFinite()) return null
        val total = secondsPerKm.roundToInt()
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }
}

enum class RecordType {
    MAX_WEIGHT,
    MAX_REPS,
    EST_ONE_RM,
    MAX_VOLUME_SET,
    MAX_DISTANCE,
    BEST_PACE,
    LONGEST_DURATION,
}

data class PersonalRecord(
    val type: RecordType,
    val value: Double,
    val secondaryValue: Double? = null,
)

/**
 * Detects personal records from a completed set.
 *
 * Two rules matter here:
 *
 * 1. **Only record types the tracking mode actually supports.** A plank cannot
 *    set a weight PR, and reporting one would be noise.
 * 2. **Lower is better for pace.** Every other record improves upward; pace is
 *    the exception, and treating it uniformly is the obvious bug.
 */
object PrDetector {

    fun candidates(mode: TrackingMode, set: SetPerformance): List<PersonalRecord> {
        val out = mutableListOf<PersonalRecord>()

        if (mode.usesWeight && mode.usesReps) {
            val w = set.weightKg
            val r = set.reps
            if (w != null && w > 0) out += PersonalRecord(RecordType.MAX_WEIGHT, w)
            if (r != null && r > 0) out += PersonalRecord(RecordType.MAX_REPS, r.toDouble())
            if (w != null && r != null) {
                OneRepMax.estimate(w, r)?.let {
                    out += PersonalRecord(RecordType.EST_ONE_RM, it, secondaryValue = r.toDouble())
                }
                val volume = Volume.forSet(mode, set)
                if (volume > 0) out += PersonalRecord(RecordType.MAX_VOLUME_SET, volume)
            }
        } else if (mode.usesReps) {
            set.reps?.takeIf { it > 0 }
                ?.let { out += PersonalRecord(RecordType.MAX_REPS, it.toDouble()) }
        }

        if (mode.usesDistance) {
            set.distanceM?.takeIf { it > 0 }
                ?.let { out += PersonalRecord(RecordType.MAX_DISTANCE, it) }
            Pace.secondsPerKm(set.distanceM, set.durationSec)
                ?.let { out += PersonalRecord(RecordType.BEST_PACE, it) }
        }

        if (mode.usesDuration && !mode.usesDistance) {
            set.durationSec?.takeIf { it > 0 }
                ?.let { out += PersonalRecord(RecordType.LONGEST_DURATION, it.toDouble()) }
        }

        return out
    }

    /** Pace improves downward; everything else improves upward. */
    fun isImprovement(type: RecordType, candidate: Double, existing: Double?): Boolean {
        if (existing == null) return true
        return if (type == RecordType.BEST_PACE) candidate < existing else candidate > existing
    }

    /** The subset of [candidates] that beat the current bests. */
    fun newRecords(
        mode: TrackingMode,
        set: SetPerformance,
        currentBests: Map<RecordType, Double>,
    ): List<PersonalRecord> =
        candidates(mode, set).filter { isImprovement(it.type, it.value, currentBests[it.type]) }
}

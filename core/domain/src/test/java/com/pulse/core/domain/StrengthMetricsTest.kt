package com.pulse.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OneRepMaxTest {

    @Test
    fun `a single rep is the one-rep max itself`() {
        assertEquals(100.0, OneRepMax.epley(100.0, 1), 1e-9)
        assertEquals(100.0, OneRepMax.brzycki(100.0, 1), 1e-9)
        assertEquals(100.0, OneRepMax.estimate(100.0, 1)!!, 1e-9)
    }

    @Test
    fun `Epley matches the published formula`() {
        // 100 * (1 + 5/30) = 116.667
        assertEquals(116.6667, OneRepMax.epley(100.0, 5), 1e-4)
    }

    @Test
    fun `Brzycki matches the published formula`() {
        // 100 * 36/(37-5) = 112.5
        assertEquals(112.5, OneRepMax.brzycki(100.0, 5), 1e-9)
    }

    @Test
    fun `the estimate sits between the two formulas`() {
        val e = OneRepMax.epley(100.0, 8)
        val b = OneRepMax.brzycki(100.0, 8)
        val est = OneRepMax.estimate(100.0, 8)!!
        assertTrue(est > minOf(e, b) && est < maxOf(e, b))
    }

    @Test
    fun `estimate is refused beyond the reliable rep range`() {
        assertNull(OneRepMax.estimate(100.0, OneRepMax.MAX_RELIABLE_REPS + 1))
        assertNull(OneRepMax.estimate(60.0, 20))
    }

    @Test
    fun `estimate rejects nonsense input rather than returning a number`() {
        assertNull(OneRepMax.estimate(0.0, 5))
        assertNull(OneRepMax.estimate(-10.0, 5))
        assertNull(OneRepMax.estimate(100.0, 0))
    }

    @Test
    fun `Brzycki is undefined at 37 reps and says so`() {
        assertFailsWith<IllegalArgumentException> { OneRepMax.brzycki(100.0, 37) }
    }

    @Test
    fun `more reps at the same weight implies a higher one-rep max`() {
        val five = OneRepMax.estimate(100.0, 5)!!
        val eight = OneRepMax.estimate(100.0, 8)!!
        assertTrue(eight > five)
    }
}

class TrackingModeTest {

    @Test
    fun `each mode declares only the dimensions it uses`() {
        with(TrackingMode.WEIGHT_REPS) {
            assertTrue(usesWeight && usesReps)
            assertTrue(!usesDuration && !usesDistance)
        }
        with(TrackingMode.DURATION) {
            assertTrue(usesDuration)
            assertTrue(!usesWeight && !usesReps && !usesDistance)
        }
        with(TrackingMode.DISTANCE_DURATION) {
            assertTrue(usesDistance && usesDuration)
            assertTrue(!usesWeight && !usesReps)
        }
        with(TrackingMode.REPS_ONLY) {
            assertTrue(usesReps)
            assertTrue(!usesWeight)
        }
    }
}

class VolumeTest {

    @Test
    fun `volume is weight times reps for ordinary loaded work`() {
        val v = Volume.forSet(TrackingMode.WEIGHT_REPS, SetPerformance(weightKg = 100.0, reps = 5))
        assertEquals(500.0, v, 1e-9)
    }

    @Test
    fun `a weighted dip moves bodyweight plus the added load`() {
        val v = Volume.forSet(
            TrackingMode.WEIGHTED_BODYWEIGHT,
            SetPerformance(weightKg = 20.0, reps = 10, bodyweightKg = 80.0),
        )
        assertEquals(1000.0, v, 1e-9) // (80 + 20) * 10
    }

    @Test
    fun `assistance subtracts from bodyweight`() {
        val v = Volume.forSet(
            TrackingMode.ASSISTED_BODYWEIGHT,
            SetPerformance(weightKg = 30.0, reps = 10, bodyweightKg = 80.0),
        )
        assertEquals(500.0, v, 1e-9) // (80 - 30) * 10
    }

    @Test
    fun `assistance greater than bodyweight clamps at zero, not negative`() {
        val v = Volume.forSet(
            TrackingMode.ASSISTED_BODYWEIGHT,
            SetPerformance(weightKg = 100.0, reps = 5, bodyweightKg = 80.0),
        )
        assertEquals(0.0, v, 1e-9)
    }

    @Test
    fun `a plank or a run has no volume rather than a fabricated number`() {
        assertEquals(0.0, Volume.forSet(TrackingMode.DURATION, SetPerformance(durationSec = 60)), 1e-9)
        assertEquals(
            0.0,
            Volume.forSet(
                TrackingMode.DISTANCE_DURATION,
                SetPerformance(distanceM = 5000.0, durationSec = 1500),
            ),
            1e-9,
        )
    }

    @Test
    fun `total sums across sets`() {
        val sets = listOf(
            SetPerformance(weightKg = 100.0, reps = 5),
            SetPerformance(weightKg = 90.0, reps = 8),
        )
        assertEquals(500.0 + 720.0, Volume.total(TrackingMode.WEIGHT_REPS, sets), 1e-9)
    }
}

class PaceTest {

    @Test
    fun `pace is seconds per kilometre`() {
        // 5 km in 25 min = 300 s/km
        assertEquals(300.0, Pace.secondsPerKm(5000.0, 1500)!!, 1e-9)
    }

    @Test
    fun `pace formats as minutes and seconds`() {
        assertEquals("5:00", Pace.format(300.0))
        assertEquals("4:30", Pace.format(270.0))
        assertEquals("5:05", Pace.format(305.0))
    }

    @Test
    fun `missing or zero inputs give no pace rather than infinity`() {
        assertNull(Pace.secondsPerKm(null, 1500))
        assertNull(Pace.secondsPerKm(5000.0, null))
        assertNull(Pace.secondsPerKm(0.0, 1500))
        assertNull(Pace.secondsPerKm(5000.0, 0))
        assertNull(Pace.format(null))
    }
}

class PrDetectorTest {

    @Test
    fun `a loaded set offers weight, reps, one-rep max and volume records`() {
        val prs = PrDetector.candidates(
            TrackingMode.WEIGHT_REPS,
            SetPerformance(weightKg = 100.0, reps = 5),
        ).map { it.type }.toSet()

        assertEquals(
            setOf(
                RecordType.MAX_WEIGHT,
                RecordType.MAX_REPS,
                RecordType.EST_ONE_RM,
                RecordType.MAX_VOLUME_SET,
            ),
            prs,
        )
    }

    @Test
    fun `a plank cannot set a weight record`() {
        val prs = PrDetector.candidates(TrackingMode.DURATION, SetPerformance(durationSec = 120))
        assertEquals(listOf(RecordType.LONGEST_DURATION), prs.map { it.type })
    }

    @Test
    fun `a run offers distance and pace, not duration`() {
        val prs = PrDetector.candidates(
            TrackingMode.DISTANCE_DURATION,
            SetPerformance(distanceM = 5000.0, durationSec = 1500),
        ).map { it.type }.toSet()

        assertEquals(setOf(RecordType.MAX_DISTANCE, RecordType.BEST_PACE), prs)
    }

    @Test
    fun `bodyweight-only work offers a reps record`() {
        val prs = PrDetector.candidates(TrackingMode.REPS_ONLY, SetPerformance(reps = 15))
        assertEquals(listOf(RecordType.MAX_REPS), prs.map { it.type })
    }

    @Test
    fun `lower is better for pace, higher for everything else`() {
        assertTrue(PrDetector.isImprovement(RecordType.BEST_PACE, candidate = 280.0, existing = 300.0))
        assertTrue(!PrDetector.isImprovement(RecordType.BEST_PACE, candidate = 320.0, existing = 300.0))

        assertTrue(PrDetector.isImprovement(RecordType.MAX_WEIGHT, candidate = 110.0, existing = 100.0))
        assertTrue(!PrDetector.isImprovement(RecordType.MAX_WEIGHT, candidate = 90.0, existing = 100.0))
    }

    @Test
    fun `the first ever attempt is always a record`() {
        assertTrue(PrDetector.isImprovement(RecordType.MAX_WEIGHT, 60.0, existing = null))
        assertTrue(PrDetector.isImprovement(RecordType.BEST_PACE, 400.0, existing = null))
    }

    @Test
    fun `only records that beat the current best are returned`() {
        val bests = mapOf(
            RecordType.MAX_WEIGHT to 120.0,      // not beaten by 100
            RecordType.MAX_REPS to 3.0,          // beaten by 5
            RecordType.EST_ONE_RM to 200.0,      // not beaten
            RecordType.MAX_VOLUME_SET to 100.0,  // beaten by 500
        )
        val new = PrDetector.newRecords(
            TrackingMode.WEIGHT_REPS,
            SetPerformance(weightKg = 100.0, reps = 5),
            bests,
        ).map { it.type }.toSet()

        assertEquals(setOf(RecordType.MAX_REPS, RecordType.MAX_VOLUME_SET), new)
    }

    @Test
    fun `a high-rep set records reps and volume but no one-rep max estimate`() {
        val prs = PrDetector.candidates(
            TrackingMode.WEIGHT_REPS,
            SetPerformance(weightKg = 40.0, reps = 20),
        ).map { it.type }.toSet()

        assertTrue(RecordType.EST_ONE_RM !in prs, "20 reps is outside the reliable range")
        assertTrue(RecordType.MAX_REPS in prs)
        assertTrue(RecordType.MAX_VOLUME_SET in prs)
    }
}

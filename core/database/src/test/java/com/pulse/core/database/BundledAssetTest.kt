package com.pulse.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pulse.core.database.entity.TrackingMode
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opens the **real** bundled databases through Room.
 *
 * This is the test that proves the whole chain works end to end: a Python
 * importer writes SQLite, `stamp_room_identity.py` validates it against the
 * KSP-exported schema and stamps the identity hash, and Room then accepts it at
 * runtime. Any drift between the importer and the Kotlin entities fails here
 * rather than on a user's device.
 *
 * Skips (rather than fails) when the assets haven't been built, so the suite
 * still runs on a clean checkout:
 *
 *     python tools/build_exercise_db.py
 *     python tools/stamp_room_identity.py
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BundledAssetTest {

    private var db: PulseDatabase? = null

    @After
    fun tearDown() {
        db?.close()
    }

    private fun projectRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: return null)
        repeat(6) {
            if (dir == null) return null
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir!!.parentFile
        }
        return null
    }

    private fun asset(name: String): File? =
        projectRoot()?.let { File(it, "data/build/$name") }?.takeIf { it.exists() }

    private fun openFrom(file: File): PulseDatabase =
        Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PulseDatabase::class.java,
            "bundled-${file.name}",
        )
            .createFromFile(file)
            .allowMainThreadQueries()
            .build()
            .also { db = it }

    // --- exercises ----------------------------------------------------------

    @Test
    fun `room opens the bundled exercise database`() = runTest {
        val file = asset("exercises.db")
        assumeTrue("exercises.db not built — run tools/build_exercise_db.py", file != null)

        val dao = openFrom(file!!).exerciseDao()

        val count = dao.count()
        assertTrue("expected a populated exercise library, got $count", count > 500)
    }

    /**
     * The invariant no unit test could check while the seed was only a script:
     * every exercise must carry a tracking mode the logging UI understands. A
     * wrong-but-valid enum makes the app show the wrong inputs.
     */
    @Test
    fun `every seeded exercise has a valid tracking mode`() = runTest {
        val file = asset("exercises.db")
        assumeTrue("exercises.db not built", file != null)

        val dao = openFrom(file!!).exerciseDao()
        assertEquals(
            "every exercise must map to a TrackingMode the set-logging UI can render",
            0,
            dao.countInvalidTrackingModes(TrackingMode.ALL),
        )
    }

    @Test
    fun `exercise search works against the bundled fts index`() = runTest {
        val file = asset("exercises.db")
        assumeTrue("exercises.db not built", file != null)

        val dao = openFrom(file!!).exerciseDao()

        val bench = dao.search("bench press")
        assertTrue("expected bench press results", bench.isNotEmpty())
        assertTrue(bench.any { it.name.contains("Bench Press", ignoreCase = true) })

        val squat = dao.search("squat")
        assertTrue("expected squat results", squat.isNotEmpty())
    }

    @Test
    fun `bundled exercises carry muscle, equipment and instructions`() = runTest {
        val file = asset("exercises.db")
        assumeTrue("exercises.db not built", file != null)

        val dao = openFrom(file!!).exerciseDao()
        val results = dao.search("bench press")
        val ex = results.first()

        assertTrue("primaryMuscle must be populated", ex.primaryMuscle.isNotBlank())
        assertTrue("equipment must be populated", ex.equipment.isNotBlank())
        assertNotNull("instructions should be present", ex.instructions)
        assertTrue("instructions are stored as a JSON array", ex.instructions!!.startsWith("["))
    }

    @Test
    fun `filtering by muscle and equipment returns sensible subsets`() = runTest {
        val file = asset("exercises.db")
        assumeTrue("exercises.db not built", file != null)

        val dao = openFrom(file!!).exerciseDao()

        val chest = dao.observeFiltered(muscle = "CHEST").first()
        assertTrue("expected chest exercises", chest.isNotEmpty())
        assertTrue("filter must not leak other muscles", chest.all { it.primaryMuscle == "CHEST" })

        val bodyweight = dao.observeFiltered(equipment = "BODYWEIGHT").first()
        assertTrue("expected bodyweight exercises", bodyweight.isNotEmpty())
        assertTrue(bodyweight.all { it.equipment == "BODYWEIGHT" })
    }

    // --- food ---------------------------------------------------------------

    /**
     * The food asset is ~197 MB, so this is opt-in: it only runs when the file
     * is present, and it deliberately does a handful of cheap indexed lookups
     * rather than anything that would scan 326k rows.
     */
    @Test
    fun `room opens the bundled food database and resolves a barcode`() = runTest {
        val file = asset("opennutrition.db")
        assumeTrue("opennutrition.db not built — run tools/build_food_db.py --lean", file != null)

        val dao = openFrom(file!!).foodDao()

        assertTrue("expected a large food database", dao.count() > 100_000)

        // Dave's Killer Bread — a real barcode verified in the source dataset.
        val hit = dao.findByBarcode("0013764027053")
        assertNotNull("expected the bundled barcode to resolve", hit)
        assertTrue(hit!!.food.kcalPer100 > 0)
        assertTrue("every food must ship at least one serving", hit.servings.isNotEmpty())
        assertTrue(
            "a canonical 100 g/ml serving must exist",
            hit.servings.any { it.gramWeight == 100.0 },
        )
    }

    @Test
    fun `food search works against the bundled fts index`() = runTest {
        val file = asset("opennutrition.db")
        assumeTrue("opennutrition.db not built", file != null)

        val dao = openFrom(file!!).foodDao()
        val results = dao.search("chicken breast", limit = 10)

        assertTrue("expected chicken results", results.isNotEmpty())
        assertTrue(results.all { it.food.kcalPer100 >= 0 })
    }
}

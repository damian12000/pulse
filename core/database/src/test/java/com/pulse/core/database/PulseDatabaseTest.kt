package com.pulse.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pulse.core.database.entity.DiaryEntryEntity
import com.pulse.core.database.entity.FoodEntity
import com.pulse.core.database.entity.FoodServingEntity
import com.pulse.core.database.entity.FoodSource
import com.pulse.core.database.entity.GoalTargetEntity
import com.pulse.core.database.entity.MealType
import com.pulse.core.database.entity.SyncState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DAO behaviour against a real (in-memory) Room database.
 *
 * Runs under Robolectric so it executes on the JVM — there is no emulator or
 * device attached to this machine, and these invariants are too important to
 * leave until Phase 12.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PulseDatabaseTest {

    private lateinit var db: PulseDatabase

    private val now = 1_700_000_000_000L
    private val today = 20_000L // epoch day

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PulseDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    // --- helpers ------------------------------------------------------------

    private fun food(
        id: String,
        name: String,
        source: String = FoodSource.OPENNUTRITION,
        barcode: String? = null,
        confidence: String = "HIGH",
        foodType: String? = "grocery",
        kcal: Double = 400.0,
    ) = FoodEntity(
        id = id,
        source = source,
        barcode = barcode,
        name = name,
        brand = null,
        alternateNames = null,
        foodType = foodType,
        kcalPer100 = kcal,
        proteinPer100 = 20.0,
        carbsPer100 = 40.0,
        fatPer100 = 16.0,
        dataConfidence = confidence,
        createdAt = now,
        updatedAt = now,
    )

    private suspend fun insertFood(f: FoodEntity, servingGrams: Double = 50.0) {
        db.foodDao().upsert(f)
        db.foodDao().insertServings(
            listOf(
                FoodServingEntity(
                    id = "${f.id}_s0",
                    foodId = f.id,
                    label = "1 serving",
                    gramWeight = servingGrams,
                    isDefault = true,
                ),
            ),
        )
    }

    // --- schema -------------------------------------------------------------

    @Test
    fun `database opens and every dao is reachable`() {
        assertNotNull(db.foodDao())
        assertNotNull(db.diaryDao())
        assertNotNull(db.workoutDao())
        assertNotNull(db.profileDao())
        assertNotNull(db.goalDao())
        assertNotNull(db.measurementDao())
    }

    @Test
    fun `foreign keys are enforced, so cascades actually work`() = runTest {
        insertFood(food("f1", "Bread"))
        assertEquals(1, db.foodDao().servingsFor("f1").size)

        // Deleting the parent must cascade to its servings.
        db.openHelper.writableDatabase.execSQL("DELETE FROM food WHERE id = 'f1'")
        assertEquals(0, db.foodDao().servingsFor("f1").size)
    }

    // --- barcode ------------------------------------------------------------

    @Test
    fun `barcode lookup returns the food with its servings`() = runTest {
        insertFood(food("f1", "Bread", barcode = "0013764027053"))

        val hit = db.foodDao().findByBarcode("0013764027053")
        assertNotNull(hit)
        assertEquals("Bread", hit!!.food.name)
        assertEquals(1, hit.servings.size)
        assertEquals(50.0, hit.servings.first().gramWeight, 1e-9)
    }

    @Test
    fun `unknown barcode returns null rather than throwing`() = runTest {
        assertNull(db.foodDao().findByBarcode("9999999999999"))
    }

    @Test
    fun `duplicate barcodes resolve to the highest confidence row`() = runTest {
        insertFood(food("low", "Low quality", barcode = "111", confidence = "LOW"))
        insertFood(food("high", "High quality", barcode = "111", confidence = "HIGH"))
        insertFood(food("med", "Medium quality", barcode = "111", confidence = "MEDIUM"))

        assertEquals(3, db.foodDao().countByBarcode("111"))
        assertEquals("High quality", db.foodDao().findByBarcode("111")!!.food.name)
    }

    @Test
    fun `a user correction outranks bundled data on the same barcode`() = runTest {
        insertFood(food("bundled", "Bundled", barcode = "222", confidence = "HIGH"))
        insertFood(food("mine", "My correction", barcode = "222",
            source = FoodSource.USER, confidence = "MEDIUM"))

        // USER wins even at lower confidence — the user knows better than the dataset.
        assertEquals("My correction", db.foodDao().findByBarcode("222")!!.food.name)
    }

    @Test
    fun `soft-deleted foods are excluded from barcode lookup`() = runTest {
        insertFood(food("f1", "Gone", barcode = "333"))
        db.foodDao().softDelete("f1", now)
        assertNull(db.foodDao().findByBarcode("333"))
    }

    // --- search -------------------------------------------------------------

    @Test
    fun `fts search finds by name and ranks generics above branded`() = runTest {
        insertFood(food("g1", "Chicken Breast", foodType = "everyday"))
        insertFood(food("b1", "Chicken Breast Strips", foodType = "grocery"))

        val results = db.foodDao().search("chicken")
        assertEquals(2, results.size)
        // 'everyday' generic must come first — a bare query shouldn't surface
        // the 400th branded product ahead of the obvious answer.
        assertEquals("Chicken Breast", results.first().food.name)
    }

    @Test
    fun `fts search matches alternate names`() = runTest {
        db.foodDao().upsert(
            food("e1", "Large Eggs", foodType = "everyday")
                .copy(alternateNames = "xl eggs jumbo eggs extra large eggs"),
        )
        val results = db.foodDao().search("jumbo")
        assertEquals(1, results.size)
        assertEquals("Large Eggs", results.first().food.name)
    }

    @Test
    fun `user foods outrank everything in search`() = runTest {
        insertFood(food("gen", "Protein Shake", foodType = "everyday"))
        insertFood(food("mine", "Protein Shake", source = FoodSource.USER, foodType = null))

        assertEquals("mine", db.foodDao().search("protein").first().food.id)
    }

    // --- usage --------------------------------------------------------------

    @Test
    fun `recording use drives recent and frequent`() = runTest {
        insertFood(food("a", "Apple"))
        insertFood(food("b", "Banana"))

        db.foodDao().recordUse("a", now)
        db.foodDao().recordUse("a", now + 1)
        db.foodDao().recordUse("b", now + 2)

        // 'b' used most recently
        assertEquals("Banana", db.foodDao().observeRecent().first().first().food.name)
        // 'a' used most often
        assertEquals("Apple", db.foodDao().observeFrequent().first().first().food.name)
        assertEquals(2, db.foodDao().usageFor("a")!!.useCount)
    }

    // --- diary --------------------------------------------------------------

    private fun entry(id: String, meal: String, kcal: Double, date: Long = today) =
        DiaryEntryEntity(
            id = id,
            date = date,
            mealType = meal,
            foodId = "f1",
            servingId = null,
            quantity = 1.0,
            grams = 50.0,
            kcal = kcal,
            protein = 10.0,
            carbs = 20.0,
            fat = 8.0,
            loggedAt = now,
            createdAt = now,
            updatedAt = now,
        )

    @Test
    fun `daily totals sum only the requested day and skip deleted rows`() = runTest {
        insertFood(food("f1", "Bread"))
        db.diaryDao().upsertAll(
            listOf(
                entry("e1", MealType.BREAKFAST, 200.0),
                entry("e2", MealType.LUNCH, 300.0),
                entry("e3", MealType.DINNER, 500.0, date = today + 1), // different day
            ),
        )

        var totals = db.diaryDao().observeDailyTotals(today).first()
        assertEquals(500.0, totals.kcal, 1e-9)
        assertEquals(20.0, totals.protein, 1e-9)

        db.diaryDao().softDelete("e2", now)
        totals = db.diaryDao().observeDailyTotals(today).first()
        assertEquals(200.0, totals.kcal, 1e-9)
    }

    @Test
    fun `empty day totals zero rather than null`() = runTest {
        val totals = db.diaryDao().observeDailyTotals(today).first()
        assertEquals(0.0, totals.kcal, 1e-9)
        assertEquals(0.0, totals.sodiumMg, 1e-9)
    }

    @Test
    fun `day entries come back in meal order, not insertion order`() = runTest {
        insertFood(food("f1", "Bread"))
        db.diaryDao().upsertAll(
            listOf(
                entry("e1", MealType.SNACK, 100.0),
                entry("e2", MealType.BREAKFAST, 200.0),
                entry("e3", MealType.DINNER, 300.0),
            ),
        )
        val meals = db.diaryDao().observeDay(today).first().map { it.mealType }
        assertEquals(listOf(MealType.BREAKFAST, MealType.DINNER, MealType.SNACK), meals)
    }

    /**
     * The load-bearing invariant from PHASE2_ARCHITECTURE.md §4.3: a diary entry
     * keeps the nutrition it was logged with, even when the underlying food is
     * later corrected.
     */
    @Test
    fun `editing a food does not rewrite history`() = runTest {
        insertFood(food("f1", "Bread", kcal = 400.0))
        db.diaryDao().upsert(entry("e1", MealType.LUNCH, 200.0))

        // upstream correction: the food was actually far more calorific
        db.foodDao().upsert(food("f1", "Bread", kcal = 900.0))

        assertEquals(200.0, db.diaryDao().observeDailyTotals(today).first().kcal, 1e-9)
        assertEquals(200.0, db.diaryDao().findById("e1")!!.kcal, 1e-9)
    }

    // --- water --------------------------------------------------------------

    @Test
    fun `water totals accumulate per day`() = runTest {
        db.waterDao().upsert(water("w1", 250))
        db.waterDao().upsert(water("w2", 500))
        assertEquals(750, db.waterDao().observeDayTotal(today).first())

        db.waterDao().softDelete("w1", now)
        assertEquals(500, db.waterDao().observeDayTotal(today).first())
    }

    private fun water(id: String, ml: Int) = com.pulse.core.database.entity.WaterEntryEntity(
        id = id, date = today, amountMl = ml, loggedAt = now,
        createdAt = now, updatedAt = now,
    )

    // --- goals --------------------------------------------------------------

    /**
     * Targets are effective-dated and append-only, so changing today's target
     * must not retroactively change what last week's target was.
     */
    @Test
    fun `effective goal resolves the latest target on or before a date`() = runTest {
        db.goalDao().upsert(goal("g1", effectiveFrom = today - 30, kcal = 2000))
        db.goalDao().upsert(goal("g2", effectiveFrom = today - 10, kcal = 2200))
        db.goalDao().upsert(goal("g3", effectiveFrom = today + 5, kcal = 2500)) // future

        assertEquals(2000, db.goalDao().effectiveOn(today - 20)!!.calorieTarget)
        assertEquals(2200, db.goalDao().effectiveOn(today)!!.calorieTarget)
        assertEquals(2500, db.goalDao().effectiveOn(today + 10)!!.calorieTarget)
        // before any target exists
        assertNull(db.goalDao().effectiveOn(today - 100))
    }

    private fun goal(id: String, effectiveFrom: Long, kcal: Int) = GoalTargetEntity(
        id = id,
        effectiveFrom = effectiveFrom,
        calorieTarget = kcal,
        proteinG = 150, carbsG = 200, fatG = 70, waterMl = 2500,
        source = "CALCULATED",
        createdAt = now, updatedAt = now,
    )

    // --- purge --------------------------------------------------------------

    @Test
    fun `purging cached data keeps rows that history still references`() = runTest {
        insertFood(food("referenced", "Logged food"))
        insertFood(food("orphan", "Never logged"))
        insertFood(food("mine", "My food", source = FoodSource.USER))

        db.diaryDao().upsert(
            entry("e1", MealType.LUNCH, 200.0).copy(foodId = "referenced"),
        )

        db.foodDao().purgeUnreferencedExternal()

        assertNotNull(db.foodDao().findById("referenced")) // still referenced
        assertNotNull(db.foodDao().findById("mine"))       // user-owned, never purged
        assertNull(db.foodDao().findById("orphan"))        // safe to drop
    }

    @Test
    fun `sync columns default sensibly`() = runTest {
        insertFood(food("f1", "Bread"))
        val f = db.foodDao().findById("f1")!!
        assertEquals(SyncState.SYNCED, f.syncState)
        assertTrue(!f.isDeleted)
        assertEquals(now, f.createdAt)
    }
}

package com.pulse.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pulse.core.database.PulseDatabase
import com.pulse.core.database.entity.FoodEntity
import com.pulse.core.database.entity.FoodServingEntity
import com.pulse.core.database.entity.FoodSource
import com.pulse.core.database.entity.MealType
import com.pulse.core.model.FoodNutrition
import com.pulse.core.network.FoodDataSource
import com.pulse.core.network.FoodSourceChain
import com.pulse.core.network.RemoteFood
import com.pulse.core.network.RemoteResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryTest {

    private lateinit var db: PulseDatabase
    private lateinit var foods: DefaultFoodRepository
    private lateinit var diary: DefaultDiaryRepository
    private lateinit var water: DefaultWaterRepository

    private val now = 1_700_000_000_000L
    private val today = 20_000L
    private val clock = Clock { now }

    /** Remote result the fake source will return; set per test. */
    private var remoteResult: RemoteResult = RemoteResult.NotFound

    private val fakeSource = object : FoodDataSource {
        override val id = "OFF"
        var callCount = 0
        override suspend fun byBarcode(ean13: String): RemoteResult {
            callCount++
            return remoteResult
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PulseDatabase::class.java,
        ).allowMainThreadQueries().build()

        val chain = FoodSourceChain(listOf(fakeSource))
        foods = DefaultFoodRepository(db.foodDao(), db.pendingLookupDao(), chain, clock)
        diary = DefaultDiaryRepository(db.diaryDao(), db.foodDao(), clock)
        water = DefaultWaterRepository(db.waterDao(), clock)
    }

    @After
    fun tearDown() = db.close()

    /** 400 kcal/100 g; the default serving is 50 g, so one serving is 200 kcal. */
    private suspend fun seedFood(
        id: String = "f1",
        barcode: String? = null,
        source: String = FoodSource.OPENNUTRITION,
        kcal: Double = 400.0,
    ) {
        db.foodDao().upsert(
            FoodEntity(
                id = id,
                source = source,
                barcode = barcode,
                name = "Test Bread",
                brand = "Acme",
                foodType = "grocery",
                kcalPer100 = kcal,
                proteinPer100 = 20.0,
                carbsPer100 = 40.0,
                fatPer100 = 16.0,
                fiberPer100 = 6.0,
                dataConfidence = "HIGH",
                createdAt = now,
                updatedAt = now,
            ),
        )
        db.foodDao().insertServings(
            listOf(
                FoodServingEntity("${id}_s0", id, "1 slice", 50.0, isDefault = true, sortOrder = 0),
                FoodServingEntity("${id}_s1", id, "100 g", 100.0, isDefault = false, sortOrder = 1),
            ),
        )
    }

    // --- barcode ------------------------------------------------------------

    @Test
    fun `resolveBarcode normalizes UPC-A before lookup`() = runTest {
        // Stored as EAN-13; the scanner hands back the 12-digit UPC-A form.
        seedFood(barcode = "0078742040370")

        val result = foods.resolveBarcode("078742040370")
        assertTrue("expected a hit after normalization, got $result", result is BarcodeResult.Found)
    }

    @Test
    fun `resolveBarcode reports unreadable input distinctly from not-found`() = runTest {
        assertTrue(foods.resolveBarcode("nonsense") is BarcodeResult.Unreadable)
        assertTrue(foods.resolveBarcode("0078742040370") is BarcodeResult.NotFound)
    }

    @Test
    fun `resolveBarcode offline reports Offline and never calls the network`() = runTest {
        val result = foods.resolveBarcode("0078742040370", online = false)
        assertTrue(result is BarcodeResult.Offline)
        assertEquals("offline must not attempt a remote call", 0, fakeSource.callCount)
        assertEquals("the miss should be queued for retry", 1, db.pendingLookupDao().count())
    }

    /** The bundled database answers most scans; the network is the exception. */
    @Test
    fun `a local hit never touches the network`() = runTest {
        seedFood(barcode = "0013764027053")
        assertTrue(foods.resolveBarcode("0013764027053") is BarcodeResult.Found)
        assertEquals(0, fakeSource.callCount)
    }

    @Test
    fun `a remote hit is cached so the second scan is local`() = runTest {
        remoteResult = RemoteResult.Found(
            RemoteFood(
                sourceId = "0013764027053",
                barcode = "0013764027053",
                name = "Remote Bread",
                brand = "Acme",
                kcalPer100 = 250.0,
                proteinPer100 = 12.0,
                carbsPer100 = 43.0,
                fatPer100 = 4.5,
                servingLabel = "1 slice (45 g)",
                servingGrams = 45.0,
            ),
        )

        val first = foods.resolveBarcode("0013764027053")
        assertTrue("expected Found, got $first", first is BarcodeResult.Found)
        assertEquals("Remote Bread", (first as BarcodeResult.Found).food.food.name)
        assertEquals(1, fakeSource.callCount)

        // Second scan resolves locally — no second network call.
        val second = foods.resolveBarcode("0013764027053")
        assertTrue(second is BarcodeResult.Found)
        assertEquals("cached, so no repeat lookup", 1, fakeSource.callCount)
    }

    @Test
    fun `a cached remote food is tagged with its source, not marked as the user's`() = runTest {
        remoteResult = RemoteResult.Found(
            RemoteFood(
                sourceId = "111", barcode = "0000000001113", name = "Remote",
                brand = null, kcalPer100 = 100.0, proteinPer100 = 5.0,
                carbsPer100 = 10.0, fatPer100 = 2.0,
            ),
        )
        foods.resolveBarcode("0000000001113")

        val cached = db.foodDao().findByBarcode("0000000001113")!!.food
        assertEquals("OFF", cached.source)
        assertEquals("111", cached.sourceId)
        assertTrue("cached remote data must stay purgeable", cached.source != FoodSource.USER)
    }

    @Test
    fun `a cached remote food always gets a canonical 100g serving`() = runTest {
        remoteResult = RemoteResult.Found(
            RemoteFood(
                sourceId = "222", barcode = "0000000002226", name = "No serving info",
                brand = null, kcalPer100 = 100.0, proteinPer100 = 5.0,
                carbsPer100 = 10.0, fatPer100 = 2.0,
                servingLabel = null, servingGrams = null,
            ),
        )
        val result = foods.resolveBarcode("0000000002226")
        val servings = (result as BarcodeResult.Found).food.servings
        assertTrue("must be loggable even with no serving info", servings.isNotEmpty())
        assertTrue(servings.any { it.gramWeight == 100.0 && it.isDefault })
    }

    /**
     * The distinction that stops the app telling you to create a food that
     * already exists. A network failure is not evidence of a genuine miss.
     */
    @Test
    fun `every source failing is reported as offline, not as not-found`() = runTest {
        remoteResult = RemoteResult.Failed("network down", retryable = true)

        val result = foods.resolveBarcode("0013764027053")
        assertTrue("expected Offline, got $result", result is BarcodeResult.Offline)
        assertEquals("should be queued for retry", 1, db.pendingLookupDao().count())
    }

    @Test
    fun `a genuine remote miss is NotFound so the user can create it`() = runTest {
        remoteResult = RemoteResult.NotFound

        val result = foods.resolveBarcode("0013764027053")
        assertTrue("expected NotFound, got $result", result is BarcodeResult.NotFound)
        assertEquals("a real miss is not worth retrying", 0, db.pendingLookupDao().count())
    }

    @Test
    fun `a food with no nutrition at all is Incomplete, not Found`() = runTest {
        seedFood(id = "empty", barcode = "0013764027053", kcal = 0.0)
        db.foodDao().upsert(
            db.foodDao().findById("empty")!!.copy(
                proteinPer100 = 0.0, carbsPer100 = 0.0, fatPer100 = 0.0,
            ),
        )

        val result = foods.resolveBarcode("0013764027053")
        assertTrue("expected Incomplete, got $result", result is BarcodeResult.Incomplete)
        assertTrue(NutrientField.CALORIES in (result as BarcodeResult.Incomplete).missing)
    }

    // --- search -------------------------------------------------------------

    @Test
    fun `search survives characters that are FTS syntax`() = runTest {
        seedFood()
        // A raw quote reaches SQLite's FTS parser as syntax; unescaped it is a
        // crash, not a no-match. Each of these must return safely.
        for (q in listOf("\"", "bread\"", "a*b", "(bread)", "bread OR", "-bread", "^")) {
            val results = foods.search(q)
            assertNotNull("query $q should not throw", results)
        }
    }

    @Test
    fun `search matches a partial trailing word so it feels live`() = runTest {
        seedFood()
        assertTrue("prefix search should match", foods.search("bre").isNotEmpty())
    }

    @Test
    fun `blank search returns nothing rather than everything`() = runTest {
        seedFood()
        assertTrue(foods.search("").isEmpty())
        assertTrue(foods.search("   ").isEmpty())
        assertTrue(foods.search("!!!").isEmpty())
    }

    // --- copy-on-write ------------------------------------------------------

    /**
     * The invariant that keeps bundled data identifiable and re-derivable:
     * editing an external food must never mutate it.
     */
    @Test
    fun `editing an external food copies rather than mutating it`() = runTest {
        seedFood(id = "bundled", source = FoodSource.OPENNUTRITION)

        val newId = foods.editFood(
            "bundled",
            FoodDraft(
                name = "My corrected bread",
                nutrition = FoodNutrition(300.0, 15.0, 30.0, 12.0),
                servingLabel = "1 slice",
                servingGrams = 45.0,
            ),
        )

        assertNotEquals("must be a new row", "bundled", newId)

        val original = db.foodDao().findById("bundled")!!
        assertEquals("original name must be untouched", "Test Bread", original.name)
        assertEquals(400.0, original.kcalPer100, 1e-9)
        assertEquals(FoodSource.OPENNUTRITION, original.source)

        val copy = db.foodDao().findById(newId)!!
        assertEquals(FoodSource.USER, copy.source)
        assertEquals("bundled", copy.derivedFromFoodId)
        assertEquals(300.0, copy.kcalPer100, 1e-9)
    }

    @Test
    fun `editing a food the user already owns updates it in place`() = runTest {
        val id = foods.createUserFood(
            FoodDraft(
                name = "My shake",
                nutrition = FoodNutrition(100.0, 20.0, 5.0, 1.0),
                servingLabel = "1 scoop",
                servingGrams = 30.0,
            ),
        )

        val sameId = foods.editFood(
            id,
            FoodDraft(
                name = "My shake v2",
                nutrition = FoodNutrition(110.0, 22.0, 5.0, 1.0),
                servingLabel = "1 scoop",
                servingGrams = 30.0,
            ),
        )

        assertEquals("no proliferation of copies for user-owned food", id, sameId)
        assertEquals("My shake v2", db.foodDao().findById(id)!!.name)
    }

    @Test
    fun `a user food is confidence-scored by the same rule as bundled data`() = runTest {
        // 20p + 40c + 16f = 384 kcal, but 100 stated -> wildly inconsistent
        val id = foods.createUserFood(
            FoodDraft(
                name = "Typo food",
                nutrition = FoodNutrition(100.0, 20.0, 40.0, 16.0),
                servingLabel = "1 serving",
                servingGrams = 50.0,
            ),
        )
        assertEquals("LOW", db.foodDao().findById(id)!!.dataConfidence)
    }

    @Test
    fun `a user food always gets a canonical 100g serving alongside its own`() = runTest {
        val id = foods.createUserFood(
            FoodDraft(
                name = "Custom",
                nutrition = FoodNutrition(200.0, 10.0, 20.0, 8.0),
                servingLabel = "1 bar",
                servingGrams = 60.0,
            ),
        )
        val servings = db.foodDao().servingsFor(id)
        assertEquals(2, servings.size)
        assertTrue(servings.any { it.gramWeight == 100.0 })
        assertTrue(servings.any { it.gramWeight == 60.0 && it.isDefault })
    }

    @Test
    fun `a user food whose serving is already 100g does not get a duplicate`() = runTest {
        val id = foods.createUserFood(
            FoodDraft(
                name = "Per hundred",
                nutrition = FoodNutrition(200.0, 10.0, 20.0, 8.0),
                servingLabel = "100 g",
                servingGrams = 100.0,
            ),
        )
        assertEquals(1, db.foodDao().servingsFor(id).size)
    }

    // --- logging ------------------------------------------------------------

    @Test
    fun `logging a food snapshots scaled nutrition onto the entry`() = runTest {
        seedFood()
        val entryId = diary.logFood(today, MealType.LUNCH, "f1", "f1_s0", quantity = 2.5)

        val entry = db.diaryDao().findById(entryId)!!
        // 50 g serving x 2.5 = 125 g; at 400 kcal/100 g that's 500 kcal
        assertEquals(125.0, entry.grams, 1e-9)
        assertEquals(500.0, entry.kcal, 1e-9)
        assertEquals(25.0, entry.protein, 1e-9)
        assertEquals(50.0, entry.carbs, 1e-9)
        assertEquals(20.0, entry.fat, 1e-9)
        assertEquals(7.5, entry.fiber!!, 1e-9)
    }

    @Test
    fun `logging records usage so the food surfaces in recents`() = runTest {
        seedFood()
        diary.logFood(today, MealType.LUNCH, "f1", null, 1.0)

        val recent = foods.observeRecent().first()
        assertEquals(1, recent.size)
        assertEquals("f1", recent.first().food.id)
        assertEquals(1, db.foodDao().usageFor("f1")!!.useCount)
    }

    @Test
    fun `logging falls back to the default serving when none is given`() = runTest {
        seedFood()
        val entryId = diary.logFood(today, MealType.LUNCH, "f1", servingId = null, quantity = 1.0)
        assertEquals(50.0, db.diaryDao().findById(entryId)!!.grams, 1e-9)
    }

    @Test
    fun `a non-positive quantity is rejected rather than logged`() = runTest {
        seedFood()
        for (q in listOf(0.0, -1.0)) {
            val threw = runCatching { diary.logFood(today, MealType.LUNCH, "f1", null, q) }.isFailure
            assertTrue("quantity $q must be rejected", threw)
        }
    }

    /**
     * Rescaling must work from the stored snapshot, not by re-reading the food —
     * correcting "2 servings" to "3" must not silently pull in an upstream
     * nutrition change the user never saw.
     */
    @Test
    fun `updating quantity rescales the snapshot and ignores upstream changes`() = runTest {
        seedFood()
        val entryId = diary.logFood(today, MealType.LUNCH, "f1", "f1_s0", quantity = 1.0)

        // Upstream "correction" after the fact
        db.foodDao().upsert(db.foodDao().findById("f1")!!.copy(kcalPer100 = 900.0))

        diary.updateQuantity(entryId, 3.0)

        val entry = db.diaryDao().findById(entryId)!!
        assertEquals(3.0, entry.quantity, 1e-9)
        assertEquals(150.0, entry.grams, 1e-9)
        assertEquals("must scale 200 -> 600, not adopt the new 900/100g", 600.0, entry.kcal, 1e-9)
    }

    @Test
    fun `daily totals reflect logging and deletion`() = runTest {
        seedFood()
        diary.logFood(today, MealType.BREAKFAST, "f1", "f1_s0", 1.0)
        val second = diary.logFood(today, MealType.DINNER, "f1", "f1_s0", 2.0)

        assertEquals(600.0, diary.observeDailyTotals(today).first().kcal, 1e-9)

        diary.deleteEntry(second)
        assertEquals(200.0, diary.observeDailyTotals(today).first().kcal, 1e-9)
    }

    @Test
    fun `copying a meal duplicates its entries onto another day`() = runTest {
        seedFood()
        diary.logFood(today, MealType.BREAKFAST, "f1", "f1_s0", 1.0)
        diary.logFood(today, MealType.BREAKFAST, "f1", "f1_s1", 1.0)

        val copied = diary.copyMeal(today, MealType.BREAKFAST, today + 1, MealType.BREAKFAST)

        assertEquals(2, copied)
        assertEquals(600.0, diary.observeDailyTotals(today + 1).first().kcal, 1e-9)
        // source day untouched
        assertEquals(600.0, diary.observeDailyTotals(today).first().kcal, 1e-9)
    }

    @Test
    fun `copying an empty meal is a no-op`() = runTest {
        assertEquals(0, diary.copyMeal(today, MealType.BREAKFAST, today + 1, MealType.LUNCH))
    }

    // --- water --------------------------------------------------------------

    @Test
    fun `water accumulates and can be removed`() = runTest {
        water.add(today, 250)
        val second = water.add(today, 500)
        assertEquals(750, water.observeDayTotal(today).first())

        water.remove(second)
        assertEquals(250, water.observeDayTotal(today).first())
    }

    @Test
    fun `a non-positive water amount is rejected`() = runTest {
        assertTrue(runCatching { water.add(today, 0) }.isFailure)
        assertTrue(runCatching { water.add(today, -100) }.isFailure)
    }

    // --- favourites ---------------------------------------------------------

    @Test
    fun `favouriting toggles and surfaces in favourites`() = runTest {
        seedFood()

        foods.toggleFavorite("f1")
        assertEquals(1, foods.observeFavorites().first().size)

        foods.toggleFavorite("f1")
        assertTrue(foods.observeFavorites().first().isEmpty())
    }
}

class FtsQueryTest {

    @Test
    fun `the last token gets a prefix wildcard so search feels live`() {
        assertEquals("chicken brea*", prepareFtsQuery("chicken brea"))
        assertEquals("bread*", prepareFtsQuery("bread"))
    }

    @Test
    fun `FTS syntax characters are stripped rather than escaped`() {
        // Quoting would be the instinct, but FTS4 will not prefix-expand a
        // quoted phrase — `"ben"*` matches nothing. Stripping to alphanumerics
        // makes the input safe *and* keeps prefix matching working.
        assertEquals("bread*", prepareFtsQuery("bread\""))
        assertEquals("bread*", prepareFtsQuery("-bread"))
        assertEquals("bread*", prepareFtsQuery("(bread)"))
        assertEquals("a b*", prepareFtsQuery("a* b"))
    }

    @Test
    fun `bare boolean keywords are dropped, not searched for literally`() {
        assertEquals("a b*", prepareFtsQuery("a OR b"))
        assertEquals("a b*", prepareFtsQuery("a AND b"))
        assertEquals("chicken rice*", prepareFtsQuery("chicken NOT rice"))
    }

    @Test
    fun `input with no usable tokens yields null`() {
        assertEquals(null, prepareFtsQuery(""))
        assertEquals(null, prepareFtsQuery("   "))
        assertEquals(null, prepareFtsQuery("\"*^()"))
        assertEquals(null, prepareFtsQuery("OR AND"))
    }

    @Test
    fun `unicode words are preserved`() {
        assertEquals("café*", prepareFtsQuery("café"))
        assertEquals("crème brûlée*", prepareFtsQuery("crème brûlée"))
    }
}

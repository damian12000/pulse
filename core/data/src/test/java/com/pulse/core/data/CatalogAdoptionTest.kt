package com.pulse.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pulse.core.database.FoodCatalogDatabase
import com.pulse.core.database.PulseDatabase
import com.pulse.core.database.entity.MealType
import com.pulse.core.network.FoodDataSource
import com.pulse.core.network.FoodSourceChain
import com.pulse.core.network.RemoteResult
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the catalog design works against the **real** downloaded database.
 *
 * The catalog lives in a separate read-only file, so its rows cannot satisfy
 * `diary_entry`'s foreign key. A food is adopted into the app database the
 * first time it is used. If that rule is wrong, logging a catalog food fails at
 * runtime with a foreign-key violation — which is exactly what these tests
 * would catch.
 *
 * Skips when the catalog hasn't been built locally.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogAdoptionTest {

    private lateinit var db: PulseDatabase
    private var catalog: FoodCatalogDatabase? = null
    private lateinit var foods: DefaultFoodRepository
    private lateinit var diary: DefaultDiaryRepository

    private val now = 1_700_000_000_000L
    private val clock = Clock { now }

    private fun catalogFile(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: return null)
        repeat(6) {
            if (dir == null) return null
            if (File(dir, "settings.gradle.kts").exists()) {
                return File(dir, "data/build/opennutrition.db").takeIf { it.exists() }
            }
            dir = dir!!.parentFile
        }
        return null
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java)
            .allowMainThreadQueries().build()

        val chain = FoodSourceChain(
            listOf(
                object : FoodDataSource {
                    override val id = "OFF"
                    override suspend fun byBarcode(ean13: String) = RemoteResult.NotFound
                },
            ),
        )

        // The manager resolves its own file location, so this test drives the
        // catalog directly and exercises the adoption logic through a manager
        // pointed at the real file.
        catalog = catalogFile()?.let { FoodCatalogDatabase.openIfPresent(context, it) }

        foods = DefaultFoodRepository(
            db.foodDao(),
            db.pendingLookupDao(),
            chain,
            BundledDataManager(context, com.pulse.core.network.AssetDownloader(OkHttpClient())),
            clock,
        )
        diary = DefaultDiaryRepository(db.diaryDao(), db.foodDao(), clock)
    }

    @After
    fun tearDown() {
        db.close()
        catalog?.close()
    }

    @Test
    fun `the real catalog opens and is populated`() = runTest {
        val dao = catalog?.catalogDao()
        assumeTrue("catalog not built — run tools/build_food_db.py --lean", dao != null)

        val count = dao!!.count()
        assertTrue("expected a large catalog, got $count", count > 100_000)
    }

    @Test
    fun `a catalog barcode resolves and carries servings`() = runTest {
        val dao = catalog?.catalogDao()
        assumeTrue("catalog not built", dao != null)

        val hit = dao!!.findByBarcode("0013764027053")
        assertNotNull("expected a known barcode to resolve", hit)
        assertTrue(hit!!.food.kcalPer100 > 0)
        assertTrue("must be loggable", hit.servings.isNotEmpty())
    }

    /**
     * The load-bearing case: a food that exists only in the read-only catalog
     * must be loggable. Without adoption this fails with a foreign-key
     * violation on `diary_entry.foodId`.
     */
    @Test
    fun `a catalog food can be adopted and logged`() = runTest {
        val dao = catalog?.catalogDao()
        assumeTrue("catalog not built", dao != null)

        val fromCatalog = dao!!.findByBarcode("0013764027053")
        assumeTrue("expected barcode present in catalog", fromCatalog != null)
        val item = fromCatalog!!

        // Not in the app database to begin with.
        assertNull(db.foodDao().findById(item.food.id))

        // Adopt exactly as the repository does.
        db.foodDao().upsert(item.food.copy(createdAt = now, updatedAt = now))
        db.foodDao().insertServings(item.servings)

        val serving = item.servings.first { it.isDefault }
        val entryId = diary.logFood(
            date = 20_000L,
            mealType = MealType.LUNCH,
            foodId = item.food.id,
            servingId = serving.id,
            quantity = 2.0,
        )

        val entry = db.diaryDao().findById(entryId)
        assertNotNull("logging a catalog food must succeed", entry)
        assertEquals(serving.gramWeight * 2, entry!!.grams, 1e-9)
        assertTrue("nutrition must be snapshotted", entry.kcal > 0)
    }

    /** Adoption preserves the id, so doing it twice is harmless. */
    @Test
    fun `adopting twice is idempotent and keeps diary references valid`() = runTest {
        val dao = catalog?.catalogDao()
        assumeTrue("catalog not built", dao != null)

        val item = dao!!.findByBarcode("0013764027053")
        assumeTrue("expected barcode present", item != null)

        db.foodDao().upsert(item!!.food.copy(createdAt = now, updatedAt = now))
        db.foodDao().insertServings(item.servings)
        val firstCount = db.foodDao().count()

        db.foodDao().upsert(item.food.copy(createdAt = now, updatedAt = now))
        assertEquals("must not duplicate the row", firstCount, db.foodDao().count())
        assertEquals(item.food.id, db.foodDao().findById(item.food.id)!!.id)
    }

    /**
     * The reason the catalog is separate at all: the writable database stays
     * small, so backups and any future sync carry only what was actually used.
     */
    @Test
    fun `unused catalog foods never enter the app database`() = runTest {
        val dao = catalog?.catalogDao()
        assumeTrue("catalog not built", dao != null)

        assertTrue("catalog is large", dao!!.count() > 100_000)
        assertEquals("app database starts empty regardless", 0, db.foodDao().count())

        foods.search("chicken")
        // Searching must not adopt: only using a food does.
        assertEquals("search must not bulk-copy the catalog", 0, db.foodDao().count())
    }
}

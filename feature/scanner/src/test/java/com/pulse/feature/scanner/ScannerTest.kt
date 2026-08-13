package com.pulse.feature.scanner

import com.pulse.core.data.BarcodeResult
import com.pulse.core.data.FoodDraft
import com.pulse.core.data.FoodRepository
import com.pulse.core.data.NutrientField
import com.pulse.core.database.dao.FoodWithServings
import com.pulse.core.database.entity.FoodEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Multi-frame agreement.
 *
 * A single frame is easy to misread on a curved or glare-hit package, and a
 * wrong digit either resolves to the wrong product or to nothing — sending the
 * user to create a food that already exists. Requiring consecutive agreement
 * costs a few hundred milliseconds and removes that whole class of error.
 */
class ConsecutiveAgreementTest {

    @Test
    fun `a single reading is not enough`() {
        val agreement = ConsecutiveAgreement(required = 2)
        assertTrue(!agreement.accept("0013764027053"))
    }

    @Test
    fun `two matching readings are accepted`() {
        val agreement = ConsecutiveAgreement(required = 2)
        assertTrue(!agreement.accept("0013764027053"))
        assertTrue(agreement.accept("0013764027053"))
    }

    /**
     * The misread case: a stray frame must not count toward the total, and the
     * partial run before it is discarded rather than resumed.
     */
    @Test
    fun `a differing reading restarts the count`() {
        val agreement = ConsecutiveAgreement(required = 3)
        assertTrue(!agreement.accept("111")) // 111 -> 1
        assertTrue(!agreement.accept("111")) // 111 -> 2, one short
        assertTrue(!agreement.accept("222")) // misread; 111's run is discarded
        assertTrue(!agreement.accept("222")) // 222 -> 2
        assertTrue("222 reaches 3 consecutive", agreement.accept("222"))
    }

    /** A run interrupted before the threshold must start over, not resume. */
    @Test
    fun `an interrupted run does not resume where it left off`() {
        val agreement = ConsecutiveAgreement(required = 3)
        agreement.accept("111")
        agreement.accept("111")
        agreement.accept("999") // interruption
        assertTrue("111 must start from one again", !agreement.accept("111"))
        assertTrue(!agreement.accept("111"))
        assertTrue(agreement.accept("111"))
    }

    @Test
    fun `three consecutive readings reach a threshold of three`() {
        val agreement = ConsecutiveAgreement(required = 3)
        assertTrue(!agreement.accept("111"))
        assertTrue(!agreement.accept("111"))
        assertTrue(agreement.accept("111"))
    }

    /**
     * The barcode stays in the viewfinder after being read, so without this the
     * same product would fire a lookup on every single frame.
     */
    @Test
    fun `an accepted barcode is not emitted again`() {
        val agreement = ConsecutiveAgreement(required = 2)
        agreement.accept("111")
        assertTrue(agreement.accept("111"))

        repeat(10) {
            assertTrue("must not re-emit while still in view", !agreement.accept("111"))
        }
    }

    @Test
    fun `reset allows the same barcode to be scanned again`() {
        val agreement = ConsecutiveAgreement(required = 2)
        agreement.accept("111")
        assertTrue(agreement.accept("111"))

        agreement.reset()

        assertTrue(!agreement.accept("111"))
        assertTrue("after reset the same product can be scanned again", agreement.accept("111"))
    }

    @Test
    fun `a threshold of one accepts immediately`() {
        val agreement = ConsecutiveAgreement(required = 1)
        assertTrue(agreement.accept("111"))
    }

    @Test
    fun `a different product after one is accepted still works`() {
        val agreement = ConsecutiveAgreement(required = 2)
        agreement.accept("111")
        assertTrue(agreement.accept("111"))

        assertTrue(!agreement.accept("222"))
        assertTrue(agreement.accept("222"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScannerViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeRepo
    private lateinit var viewModel: ScannerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeRepo()
        viewModel = ScannerViewModel(repository)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun food(id: String = "f1") = FoodWithServings(
        food = FoodEntity(
            id = id, source = "OPENNUTRITION", name = "Bread",
            kcalPer100 = 250.0, proteinPer100 = 12.0, carbsPer100 = 43.0, fatPer100 = 4.5,
            dataConfidence = "HIGH", createdAt = 0, updatedAt = 0,
        ),
        servings = emptyList(),
    )

    @Test
    fun `a found product is offered for logging`() = runTest(dispatcher) {
        repository.result = BarcodeResult.Found(food(), com.pulse.core.model.DataConfidence.HIGH)

        viewModel.onBarcodeDetected("0013764027053")
        advanceUntilIdle()

        val scan = viewModel.uiState.value.scan
        assertTrue("expected Found, got $scan", scan is ScanState.Found)
    }

    /**
     * The distinction that stops the app telling you to recreate a product that
     * exists: a network failure is not evidence of absence.
     */
    @Test
    fun `offline maps to Unavailable, not NotFound`() = runTest(dispatcher) {
        repository.result = BarcodeResult.Offline("0013764027053")

        viewModel.onBarcodeDetected("0013764027053")
        advanceUntilIdle()

        val scan = viewModel.uiState.value.scan
        assertTrue("expected Unavailable, got $scan", scan is ScanState.Unavailable)
    }

    @Test
    fun `a genuine miss offers creation with the barcode carried through`() = runTest(dispatcher) {
        repository.result = BarcodeResult.NotFound("0013764027053", suggestedName = "Some Bread")

        viewModel.onBarcodeDetected("0013764027053")
        advanceUntilIdle()

        val scan = viewModel.uiState.value.scan as ScanState.NotFound
        assertEquals("0013764027053", scan.barcode)
        assertEquals("Some Bread", scan.suggestedName)
    }

    @Test
    fun `incomplete data is surfaced rather than silently logged`() = runTest(dispatcher) {
        repository.result = BarcodeResult.Incomplete(food(), setOf(NutrientField.CALORIES))

        viewModel.onBarcodeDetected("111")
        advanceUntilIdle()

        val scan = viewModel.uiState.value.scan
        assertTrue(scan is ScanState.Incomplete)
        assertTrue(NutrientField.CALORIES in (scan as ScanState.Incomplete).missing)
    }

    /** The barcode stays in frame; a second lookup must not fire. */
    @Test
    fun `detections are ignored while a result is showing`() = runTest(dispatcher) {
        repository.result = BarcodeResult.Found(food(), com.pulse.core.model.DataConfidence.HIGH)

        viewModel.onBarcodeDetected("111")
        advanceUntilIdle()
        assertEquals(1, repository.calls)

        viewModel.onBarcodeDetected("111")
        viewModel.onBarcodeDetected("222")
        advanceUntilIdle()
        assertEquals("no further lookups while showing a result", 1, repository.calls)
    }

    @Test
    fun `resuming allows the next scan`() = runTest(dispatcher) {
        repository.result = BarcodeResult.Found(food(), com.pulse.core.model.DataConfidence.HIGH)

        viewModel.onBarcodeDetected("111")
        advanceUntilIdle()

        viewModel.resumeScanning()
        assertTrue(viewModel.uiState.value.scan is ScanState.Scanning)

        viewModel.onBarcodeDetected("222")
        advanceUntilIdle()
        assertEquals(2, repository.calls)
    }

    /** A repository crash must not take the scanner down mid-shop. */
    @Test
    fun `a thrown lookup degrades to Unavailable instead of crashing`() = runTest(dispatcher) {
        repository.shouldThrow = true

        viewModel.onBarcodeDetected("111")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.scan is ScanState.Unavailable)
    }

    @Test
    fun `torch toggles`() {
        assertTrue(!viewModel.uiState.value.torchEnabled)
        viewModel.toggleTorch()
        assertTrue(viewModel.uiState.value.torchEnabled)
        viewModel.toggleTorch()
        assertTrue(!viewModel.uiState.value.torchEnabled)
    }
}

private class FakeRepo : FoodRepository {
    var result: BarcodeResult = BarcodeResult.NotFound("x")
    var calls = 0
    var shouldThrow = false

    override suspend fun resolveBarcode(rawBarcode: String, online: Boolean): BarcodeResult {
        calls++
        if (shouldThrow) error("database unavailable")
        return result
    }

    override suspend fun search(query: String, limit: Int) = emptyList<FoodWithServings>()
    override fun observeRecent(limit: Int): Flow<List<FoodWithServings>> = MutableStateFlow(emptyList())
    override fun observeFrequent(limit: Int): Flow<List<FoodWithServings>> = MutableStateFlow(emptyList())
    override fun observeFavorites(): Flow<List<FoodWithServings>> = MutableStateFlow(emptyList())
    override fun observeFood(foodId: String): Flow<FoodWithServings?> = MutableStateFlow(null)
    override suspend fun createUserFood(draft: FoodDraft) = "new"
    override suspend fun editFood(foodId: String, draft: FoodDraft) = foodId
    override suspend fun toggleFavorite(foodId: String) = Unit
    override suspend fun recordUse(foodId: String) = Unit
}

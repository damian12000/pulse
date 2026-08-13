package com.pulse.feature.food

import com.pulse.core.data.BarcodeResult
import com.pulse.core.data.FoodDraft
import com.pulse.core.data.FoodRepository
import com.pulse.core.database.dao.FoodWithServings
import com.pulse.core.model.DataConfidence
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreateFoodViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: RecordingFoodRepository
    private lateinit var viewModel: CreateFoodViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = RecordingFoodRepository()
        viewModel = CreateFoodViewModel(repository)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A 45 g slice at 113 kcal — figures as they appear on a real label. */
    private fun fillFromLabel() = with(viewModel) {
        onNameChange("Sourdough")
        onBrandChange("Local Bakery")
        onServingLabelChange("1 slice")
        onServingGramsChange("45")
        onKcalChange("113")
        onProteinChange("4")
        onCarbsChange("21")
        onFatChange("1")
    }

    /**
     * The core conversion: labels state per-serving figures, storage is
     * per-100 g. Asking the user to divide would invite errors on every entry.
     */
    @Test
    fun `per-serving entry is converted to per-100g for storage`() = runTest(dispatcher) {
        fillFromLabel()
        viewModel.save()
        advanceUntilIdle()

        val draft = repository.created.single()
        // 45 g -> 100 g is a factor of 100/45 = 2.222…
        assertEquals(251.1, draft.nutrition.kcalPer100, 0.1)
        assertEquals(8.9, draft.nutrition.proteinPer100, 0.1)
        assertEquals(46.7, draft.nutrition.carbsPer100, 0.1)
        assertEquals(2.2, draft.nutrition.fatPer100, 0.1)
        // The serving itself is preserved, so logging "1 slice" gives 113 kcal back.
        assertEquals(45.0, draft.servingGrams, 1e-9)
        assertEquals("1 slice", draft.servingLabel)
    }

    @Test
    fun `per-100 basis is stored as typed`() = runTest(dispatcher) {
        with(viewModel) {
            onNameChange("Oats")
            onServingGramsChange("40")
            onBasisChange(NutritionBasis.PER_100)
            onKcalChange("380")
            onProteinChange("13")
            onCarbsChange("60")
            onFatChange("8")
        }
        viewModel.save()
        advanceUntilIdle()

        val draft = repository.created.single()
        assertEquals(380.0, draft.nutrition.kcalPer100, 1e-9)
        assertEquals(40.0, draft.servingGrams, 1e-9)
    }

    /**
     * The typo-catcher. A misplaced decimal is the most common data-entry
     * error, and it produces a diary entry that looks plausible.
     */
    @Test
    fun `macros that disagree with calories produce a warning`() = runTest(dispatcher) {
        with(viewModel) {
            onNameChange("Typo")
            onServingGramsChange("100")
            onKcalChange("100")   // but the macros below imply ~384
            onProteinChange("20")
            onCarbsChange("40")
            onFatChange("16")
        }
        advanceUntilIdle()

        val warning = viewModel.uiState.value.energyWarning
        assertNotNull("a 284% discrepancy should be flagged", warning)
        assertTrue("the warning should state the implied figure", warning!!.contains("384"))
    }

    @Test
    fun `consistent macros produce no warning and high confidence`() = runTest(dispatcher) {
        fillFromLabel()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.energyWarning)
        assertEquals(DataConfidence.HIGH, viewModel.uiState.value.confidence)
    }

    /**
     * Catches the other common error: entering per-100 figures while the
     * serving is small, which yields a physically impossible density.
     */
    @Test
    fun `an impossible calorie density is flagged`() = runTest(dispatcher) {
        with(viewModel) {
            onNameChange("Wrong basis")
            onServingGramsChange("10")
            onKcalChange("380")     // 380 kcal in 10 g = 3800/100 g
            onProteinChange("13")
            onCarbsChange("60")
            onFatChange("8")
        }
        advanceUntilIdle()

        val warning = viewModel.uiState.value.energyWarning
        assertNotNull(warning)
        assertTrue("should mention the serving size", warning!!.contains("serving size"))
    }

    @Test
    fun `saving is blocked until the required fields are filled`() = runTest(dispatcher) {
        assertTrue(!viewModel.uiState.value.canSave)

        viewModel.onNameChange("Something")
        advanceUntilIdle()
        assertTrue("a name alone is not enough", !viewModel.uiState.value.canSave)

        fillFromLabel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.canSave)
    }

    @Test
    fun `attempting to save incomplete data reports per-field errors`() = runTest(dispatcher) {
        viewModel.save()
        advanceUntilIdle()

        val errors = viewModel.uiState.value.errors
        assertTrue(Field.NAME in errors)
        assertTrue(Field.SERVING_GRAMS in errors)
        assertTrue(Field.KCAL in errors)
        assertTrue("nothing should be written", repository.created.isEmpty())
    }

    /** Zero fat is a real value; a blank field is not the same thing. */
    @Test
    fun `zero is accepted but blank is not`() = runTest(dispatcher) {
        with(viewModel) {
            onNameChange("Rice cake")
            onServingGramsChange("9")
            onKcalChange("35")
            onProteinChange("0.7")
            onCarbsChange("7.5")
            onFatChange("0")
        }
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.canSave)

        viewModel.onFatChange("")
        advanceUntilIdle()
        assertTrue("blank must not pass as zero", !viewModel.uiState.value.canSave)
    }

    @Test
    fun `a failed scan pre-fills the barcode so the form is not a dead end`() = runTest(dispatcher) {
        viewModel.prefill(barcode = "0013764027053", suggestedName = "Mystery Bread")
        advanceUntilIdle()

        assertEquals("Mystery Bread", viewModel.uiState.value.name)

        viewModel.onServingGramsChange("45")
        viewModel.onKcalChange("113")
        viewModel.onProteinChange("4")
        viewModel.onCarbsChange("21")
        viewModel.onFatChange("1")
        viewModel.save()
        advanceUntilIdle()

        // The barcode must be carried through, or the next scan misses again.
        assertEquals("0013764027053", repository.created.single().barcode)
    }

    @Test
    fun `optional nutrients are converted too when provided`() = runTest(dispatcher) {
        fillFromLabel()
        viewModel.onFiberChange("2")
        viewModel.onSodiumChange("230")
        viewModel.save()
        advanceUntilIdle()

        val n = repository.created.single().nutrition
        assertEquals(4.4, n.fiberPer100!!, 0.1)
        assertEquals(511.1, n.sodiumMgPer100!!, 0.5)
        assertNull("unfilled optional fields stay unknown, not zero", n.sugarPer100)
    }

    @Test
    fun `comma decimals are accepted throughout`() = runTest(dispatcher) {
        with(viewModel) {
            onNameChange("Comma")
            onServingGramsChange("37,5")
            onKcalChange("100,5")
            onProteinChange("1,5")
            onCarbsChange("2,5")
            onFatChange("0,5")
        }
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.canSave)
    }

    @Test
    fun `saving reports the new food id so the caller can log it immediately`() = runTest(dispatcher) {
        fillFromLabel()
        viewModel.save()
        advanceUntilIdle()

        assertEquals("created-1", viewModel.uiState.value.savedFoodId)
        assertTrue(!viewModel.uiState.value.isSaving)
    }
}

class NumberParsingTest {

    @Test
    fun `positive parsing rejects zero and negatives`() {
        assertEquals(45.0, "45".toPositiveOrNull()!!, 1e-9)
        assertEquals(37.5, "37,5".toPositiveOrNull()!!, 1e-9)
        assertNull("0".toPositiveOrNull())
        assertNull("-5".toPositiveOrNull())
        assertNull("".toPositiveOrNull())
        assertNull("abc".toPositiveOrNull())
    }

    @Test
    fun `non-negative parsing accepts zero but not blank`() {
        assertEquals(0.0, "0".toNonNegativeOrNull()!!, 1e-9)
        assertEquals(1.5, "1.5".toNonNegativeOrNull()!!, 1e-9)
        assertNull("".toNonNegativeOrNull())
        assertNull("-1".toNonNegativeOrNull())
        assertNull("NaN".toNonNegativeOrNull())
    }
}

private class RecordingFoodRepository : FoodRepository {
    val created = mutableListOf<FoodDraft>()

    override suspend fun resolveBarcode(rawBarcode: String, online: Boolean) =
        BarcodeResult.NotFound(rawBarcode)
    override suspend fun search(query: String, limit: Int) = emptyList<FoodWithServings>()
    override fun observeRecent(limit: Int): Flow<List<FoodWithServings>> = MutableStateFlow(emptyList())
    override fun observeFrequent(limit: Int): Flow<List<FoodWithServings>> = MutableStateFlow(emptyList())
    override fun observeFavorites(): Flow<List<FoodWithServings>> = MutableStateFlow(emptyList())
    override fun observeFood(foodId: String): Flow<FoodWithServings?> = MutableStateFlow(null)

    override suspend fun createUserFood(draft: FoodDraft): String {
        created += draft
        return "created-${created.size}"
    }

    override suspend fun editFood(foodId: String, draft: FoodDraft): String {
        created += draft
        return foodId
    }

    override suspend fun toggleFavorite(foodId: String) = Unit
    override suspend fun recordUse(foodId: String) = Unit
}

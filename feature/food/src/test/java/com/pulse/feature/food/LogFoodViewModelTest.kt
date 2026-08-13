package com.pulse.feature.food

import com.pulse.core.data.BarcodeResult
import com.pulse.core.data.DiaryRepository
import com.pulse.core.data.FoodDraft
import com.pulse.core.data.FoodRepository
import com.pulse.core.database.dao.DailyTotals
import com.pulse.core.database.dao.FoodWithServings
import com.pulse.core.database.dao.MealTotals
import com.pulse.core.database.entity.DiaryEntryEntity
import com.pulse.core.database.entity.FoodEntity
import com.pulse.core.database.entity.FoodServingEntity
import com.pulse.core.domain.Meal
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogFoodViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var foods: FakeFoods
    private lateinit var diary: FakeDiary
    private lateinit var viewModel: LogFoodViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        foods = FakeFoods()
        diary = FakeDiary()
        viewModel = LogFoodViewModel(foods, diary)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** 400 kcal/100 g. Default serving 50 g ⇒ one serving is 200 kcal. */
    private fun seed(id: String = "f1") {
        foods.food.value = FoodWithServings(
            food = FoodEntity(
                id = id, source = "OPENNUTRITION", name = "Test Bread", brand = "Acme",
                kcalPer100 = 400.0, proteinPer100 = 20.0, carbsPer100 = 40.0, fatPer100 = 16.0,
                dataConfidence = "HIGH", createdAt = 0, updatedAt = 0,
            ),
            servings = listOf(
                FoodServingEntity("${id}_s0", id, "1 slice", 50.0, isDefault = true, sortOrder = 0),
                FoodServingEntity("${id}_s1", id, "100 g", 100.0, isDefault = false, sortOrder = 1),
            ),
        )
    }

    @Test
    fun `opens pre-filled with the default serving and a quantity of one`() = runTest(dispatcher) {
        seed()
        val job = collect()
        viewModel.setFood("f1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("1 slice", state.selectedServing?.label)
        assertEquals("1", state.quantityText)
        assertTrue("should be loggable immediately", state.isLoggable)
        // One tap from here should log — nothing else needs filling in.
        assertEquals(200.0, state.nutrition.kcal, 1e-9)
        job.cancel()
    }

    @Test
    fun `nutrition updates live as the quantity changes`() = runTest(dispatcher) {
        seed()
        val job = collect()
        viewModel.setFood("f1")
        advanceUntilIdle()

        viewModel.onQuantityChange("2.5")
        advanceUntilIdle()

        val n = viewModel.uiState.value.nutrition
        assertEquals(500.0, n.kcal, 1e-9)
        assertEquals(25.0, n.proteinG, 1e-9)
        assertEquals(50.0, n.carbsG, 1e-9)
        assertEquals(20.0, n.fatG, 1e-9)
        job.cancel()
    }

    @Test
    fun `switching serving rescales`() = runTest(dispatcher) {
        seed()
        val job = collect()
        viewModel.setFood("f1")
        advanceUntilIdle()

        viewModel.onServingSelected("f1_s1") // 100 g
        advanceUntilIdle()

        assertEquals(400.0, viewModel.uiState.value.nutrition.kcal, 1e-9)
        job.cancel()
    }

    /**
     * A half-typed quantity must not silently become a zero-calorie entry —
     * the button is disabled instead.
     */
    @Test
    fun `an incomplete quantity blocks logging rather than logging zero`() = runTest(dispatcher) {
        seed()
        val job = collect()
        viewModel.setFood("f1")
        advanceUntilIdle()

        for (bad in listOf("", "  ", ".", "abc", "0", "-1")) {
            viewModel.onQuantityChange(bad)
            advanceUntilIdle()
            assertTrue("\"$bad\" must not be loggable", !viewModel.uiState.value.isLoggable)
        }

        viewModel.log()
        advanceUntilIdle()
        assertTrue("nothing should have been written", diary.logged.isEmpty())
        job.cancel()
    }

    @Test
    fun `a comma decimal separator is accepted`() = runTest(dispatcher) {
        seed()
        val job = collect()
        viewModel.setFood("f1")
        advanceUntilIdle()

        viewModel.onQuantityChange("2,5")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isLoggable)
        assertEquals(500.0, viewModel.uiState.value.nutrition.kcal, 1e-9)
        job.cancel()
    }

    @Test
    fun `logging writes the chosen serving, quantity and meal`() = runTest(dispatcher) {
        seed()
        val job = collect()
        viewModel.setFood("f1")
        advanceUntilIdle()

        viewModel.onQuantityChange("3")
        viewModel.onMealSelected(Meal.DINNER)
        advanceUntilIdle()
        viewModel.log(LocalDate.of(2026, 8, 12))
        advanceUntilIdle()

        assertEquals(1, diary.logged.size)
        val entry = diary.logged.single()
        assertEquals("f1", entry.foodId)
        assertEquals("f1_s0", entry.servingId)
        assertEquals(3.0, entry.quantity, 1e-9)
        assertEquals("DINNER", entry.mealType)
        assertEquals(LocalDate.of(2026, 8, 12).toEpochDay(), entry.date)
        job.cancel()
    }

    /** The sheet closes on success; the confirmation is the diary, not a dialog. */
    @Test
    fun `didLog flips once the entry is written`() = runTest(dispatcher) {
        seed()
        val job = collect()
        viewModel.setFood("f1")
        advanceUntilIdle()
        assertTrue(!viewModel.uiState.value.didLog)

        viewModel.log()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.didLog)
        job.cancel()
    }

    /**
     * Reopening the sheet for a different food must not inherit the previous
     * food's serving or quantity.
     */
    @Test
    fun `switching food resets serving and quantity`() = runTest(dispatcher) {
        seed()
        val job = collect()
        viewModel.setFood("f1")
        advanceUntilIdle()

        viewModel.onServingSelected("f1_s1")
        viewModel.onQuantityChange("7")
        advanceUntilIdle()

        seed("f2")
        viewModel.setFood("f2")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("1", state.quantityText)
        assertEquals("1 slice", state.selectedServing?.label)
        job.cancel()
    }

    private fun TestScope.collect(): Job = launch { viewModel.uiState.collect { } }
}

class QuantityParsingTest {

    @Test
    fun `parses plain and decimal quantities`() {
        assertEquals(1.0, "1".toQuantityOrNull()!!, 1e-9)
        assertEquals(2.5, "2.5".toQuantityOrNull()!!, 1e-9)
        assertEquals(0.5, "0.5".toQuantityOrNull()!!, 1e-9)
        assertEquals(2.5, " 2.5 ".toQuantityOrNull()!!, 1e-9)
    }

    @Test
    fun `accepts a comma decimal separator`() {
        assertEquals(2.5, "2,5".toQuantityOrNull()!!, 1e-9)
    }

    @Test
    fun `rejects anything that cannot be logged`() {
        assertNull("".toQuantityOrNull())
        assertNull("   ".toQuantityOrNull())
        assertNull("abc".toQuantityOrNull())
        assertNull(".".toQuantityOrNull())
        assertNull("0".toQuantityOrNull())
        assertNull("-1".toQuantityOrNull())
        assertNull("NaN".toQuantityOrNull())
        assertNull("Infinity".toQuantityOrNull())
    }
}

private class FakeFoods : FoodRepository {
    val food = MutableStateFlow<FoodWithServings?>(null)
    override suspend fun resolveBarcode(rawBarcode: String, online: Boolean) =
        BarcodeResult.NotFound(rawBarcode)
    override suspend fun search(query: String, limit: Int) = emptyList<FoodWithServings>()
    override fun observeRecent(limit: Int): Flow<List<FoodWithServings>> = MutableStateFlow(emptyList())
    override fun observeFrequent(limit: Int): Flow<List<FoodWithServings>> = MutableStateFlow(emptyList())
    override fun observeFavorites(): Flow<List<FoodWithServings>> = MutableStateFlow(emptyList())
    override fun observeFood(foodId: String): Flow<FoodWithServings?> = food
    override suspend fun createUserFood(draft: FoodDraft) = "new"
    override suspend fun editFood(foodId: String, draft: FoodDraft) = foodId
    override suspend fun toggleFavorite(foodId: String) = Unit
    override suspend fun recordUse(foodId: String) = Unit
}

private data class LoggedEntry(
    val date: Long,
    val mealType: String,
    val foodId: String,
    val servingId: String?,
    val quantity: Double,
)

private class FakeDiary : DiaryRepository {
    val logged = mutableListOf<LoggedEntry>()

    override fun observeDay(date: Long): Flow<List<DiaryEntryEntity>> = MutableStateFlow(emptyList())
    override fun observeMeal(date: Long, mealType: String): Flow<List<DiaryEntryEntity>> =
        MutableStateFlow(emptyList())
    override fun observeDailyTotals(date: Long): Flow<DailyTotals> =
        MutableStateFlow(DailyTotals(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
    override fun observeMealTotals(date: Long): Flow<List<MealTotals>> = MutableStateFlow(emptyList())

    override suspend fun logFood(
        date: Long,
        mealType: String,
        foodId: String,
        servingId: String?,
        quantity: Double,
    ): String {
        logged += LoggedEntry(date, mealType, foodId, servingId, quantity)
        return "entry-${logged.size}"
    }

    override suspend fun updateQuantity(entryId: String, quantity: Double) = Unit
    override suspend fun deleteEntry(entryId: String) = Unit
    override suspend fun copyMeal(fromDate: Long, fromMeal: String, toDate: Long, toMeal: String) = 0
}

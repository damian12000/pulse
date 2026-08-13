package com.pulse.feature.food

import com.pulse.core.data.BarcodeResult
import com.pulse.core.data.FoodDraft
import com.pulse.core.data.FoodRepository
import com.pulse.core.database.dao.FoodWithServings
import com.pulse.core.database.entity.FoodEntity
import com.pulse.core.model.ContentState
import com.pulse.core.model.EmptyReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FoodSearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeFoodRepository
    private lateinit var viewModel: FoodSearchViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeFoodRepository()
        viewModel = FoodSearchViewModel(repository)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun food(id: String, name: String) = FoodWithServings(
        food = FoodEntity(
            id = id, source = "OPENNUTRITION", name = name,
            kcalPer100 = 100.0, proteinPer100 = 5.0, carbsPer100 = 10.0, fatPer100 = 2.0,
            dataConfidence = "HIGH", createdAt = 0, updatedAt = 0,
        ),
        servings = emptyList(),
    )

    @Test
    fun `opens on recents rather than a blank screen`() = runTest(dispatcher) {
        repository.recent.value = listOf(food("1", "Yesterday's lunch"))

        val job = launchCollector()
        advanceUntilIdle()

        assertEquals(SearchTab.RECENT, viewModel.uiState.value.tab)
        val results = viewModel.uiState.value.results
        assertTrue("expected recents, got $results", results is ContentState.Content)
        assertEquals(1, (results as ContentState.Content).data.size)
        job.cancel()
    }

    /** Typing must not fire a query per keystroke against a 326k-row index. */
    @Test
    fun `search is debounced`() = runTest(dispatcher) {
        val job = launchCollector()
        advanceUntilIdle()
        repository.searchCalls = 0

        viewModel.onQueryChange("c")
        viewModel.onQueryChange("ch")
        viewModel.onQueryChange("chi")
        viewModel.onQueryChange("chic")
        advanceTimeBy(100)
        assertEquals("no query should fire mid-typing", 0, repository.searchCalls)

        advanceUntilIdle()
        assertEquals("exactly one query after the pause", 1, repository.searchCalls)
        assertEquals("chic", repository.lastQuery)
        job.cancel()
    }

    @Test
    fun `a typed query searches even when a non-search tab is selected`() = runTest(dispatcher) {
        repository.searchResults = listOf(food("1", "Chicken"))

        val job = launchCollector()
        advanceUntilIdle()

        viewModel.onTabChange(SearchTab.FAVORITES)
        viewModel.onQueryChange("chicken")
        advanceUntilIdle()

        // Typing while on Favourites must still search, not silently do nothing.
        assertEquals(1, repository.searchCalls)
        val results = viewModel.uiState.value.results
        assertTrue(results is ContentState.Content)
        assertEquals("Chicken", (results as ContentState.Content).data.first().food.name)
        job.cancel()
    }

    @Test
    fun `no matches is Empty with the search reason, not an empty list`() = runTest(dispatcher) {
        repository.searchResults = emptyList()

        val job = launchCollector()
        advanceUntilIdle()
        viewModel.onQueryChange("xyzzy")
        advanceUntilIdle()

        val results = viewModel.uiState.value.results
        assertTrue("expected Empty, got $results", results is ContentState.Empty)
        assertEquals(EmptyReason.NO_RESULTS, (results as ContentState.Empty).reason)
        job.cancel()
    }

    @Test
    fun `an empty recents list reads as nothing-logged, not no-results`() = runTest(dispatcher) {
        repository.recent.value = emptyList()

        val job = launchCollector()
        advanceUntilIdle()

        val results = viewModel.uiState.value.results
        assertTrue(results is ContentState.Empty)
        // These want different artwork and different next actions.
        assertEquals(EmptyReason.NOTHING_LOGGED, (results as ContentState.Empty).reason)
        job.cancel()
    }

    @Test
    fun `clearing the query returns to the selected tab`() = runTest(dispatcher) {
        repository.recent.value = listOf(food("1", "Recent item"))
        repository.searchResults = listOf(food("2", "Search hit"))

        val job = launchCollector()
        advanceUntilIdle()

        viewModel.onQueryChange("search")
        advanceUntilIdle()
        assertEquals("Search hit", firstResultName())

        viewModel.onClearQuery()
        advanceUntilIdle()
        assertEquals("Recent item", firstResultName())
        job.cancel()
    }

    @Test
    fun `switching tabs swaps the source list`() = runTest(dispatcher) {
        repository.recent.value = listOf(food("1", "Recent"))
        repository.favorites.value = listOf(food("2", "Favourite"))

        val job = launchCollector()
        advanceUntilIdle()
        assertEquals("Recent", firstResultName())

        viewModel.onTabChange(SearchTab.FAVORITES)
        advanceUntilIdle()
        assertEquals("Favourite", firstResultName())
        job.cancel()
    }

    @Test
    fun `a repository failure surfaces as an error state, not a crash`() = runTest(dispatcher) {
        repository.failSearch = true

        val job = launchCollector()
        advanceUntilIdle()
        viewModel.onQueryChange("boom")
        advanceUntilIdle()

        assertTrue(
            "expected Error, got ${viewModel.uiState.value.results}",
            viewModel.uiState.value.results is ContentState.Error,
        )
        job.cancel()
    }

    @Test
    fun `a consumed message is cleared so it is not shown twice`() = runTest(dispatcher) {
        repository.failFavorite = true

        val job = launchCollector()
        advanceUntilIdle()

        viewModel.onToggleFavorite("1")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.message != null)

        viewModel.onMessageShown()
        advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.message)
        job.cancel()
    }

    private fun firstResultName(): String? =
        (viewModel.uiState.value.results as? ContentState.Content)?.data?.firstOrNull()?.food?.name

    /** StateFlow with WhileSubscribed needs a live collector to emit. */
    private fun TestScope.launchCollector(): Job =
        launch { viewModel.uiState.collect { } }
}

private class FakeFoodRepository : FoodRepository {
    val recent = MutableStateFlow<List<FoodWithServings>>(emptyList())
    val frequent = MutableStateFlow<List<FoodWithServings>>(emptyList())
    val favorites = MutableStateFlow<List<FoodWithServings>>(emptyList())

    var searchResults: List<FoodWithServings> = emptyList()
    var searchCalls = 0
    var lastQuery: String? = null
    var failSearch = false
    var failFavorite = false

    override suspend fun resolveBarcode(rawBarcode: String, online: Boolean): BarcodeResult =
        BarcodeResult.NotFound(rawBarcode)

    override suspend fun search(query: String, limit: Int): List<FoodWithServings> {
        searchCalls++
        lastQuery = query
        if (failSearch) error("search failed")
        return searchResults
    }

    override fun observeRecent(limit: Int): Flow<List<FoodWithServings>> = recent
    override fun observeFrequent(limit: Int): Flow<List<FoodWithServings>> = frequent
    override fun observeFavorites(): Flow<List<FoodWithServings>> = favorites
    override fun observeFood(foodId: String): Flow<FoodWithServings?> = MutableStateFlow(null)

    override suspend fun createUserFood(draft: FoodDraft): String = "new"
    override suspend fun editFood(foodId: String, draft: FoodDraft): String = foodId
    override suspend fun toggleFavorite(foodId: String) {
        if (failFavorite) error("toggle failed")
    }
    override suspend fun recordUse(foodId: String) = Unit
}

package com.pulse.feature.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulse.core.data.FoodRepository
import com.pulse.core.database.dao.FoodWithServings
import com.pulse.core.model.ContentState
import com.pulse.core.model.EmptyReason
import com.pulse.core.model.ErrorKind
import com.pulse.core.model.UiMessage
import com.pulse.core.model.asContentState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which list the user is looking at. */
enum class SearchTab { ALL, RECENT, FREQUENT, FAVORITES }

data class FoodSearchUiState(
    val query: String = "",
    val tab: SearchTab = SearchTab.RECENT,
    val results: ContentState<List<FoodWithServings>> = ContentState.Loading,
    val message: UiMessage? = null,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class FoodSearchViewModel @Inject constructor(
    private val repository: FoodRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val tab = MutableStateFlow(SearchTab.RECENT)
    private val message = MutableStateFlow<UiMessage?>(null)

    /**
     * Debounced so typing doesn't fire a query per keystroke against a
     * 326k-row FTS index, but short enough to still feel live.
     */
    private val results: StateFlow<ContentState<List<FoodWithServings>>> =
        combine(query.debounce(SEARCH_DEBOUNCE_MS).distinctUntilChanged(), tab, ::Pair)
            .flatMapLatest { (text, selectedTab) -> resultsFor(text, selectedTab) }
            .catch { emit(ContentState.Error(ErrorKind.DATABASE)) }
            .stateIn(
                scope = viewModelScope,
                // Survives rotation without re-querying, but releases when the
                // screen is genuinely gone.
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = ContentState.Loading,
            )

    val uiState: StateFlow<FoodSearchUiState> =
        combine(query, tab, results, message) { q, t, r, m ->
            FoodSearchUiState(query = q, tab = t, results = r, message = m)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = FoodSearchUiState(),
        )

    private fun resultsFor(text: String, selectedTab: SearchTab) = when {
        // A typed query always searches, whichever tab is selected — otherwise
        // typing while on "Favourites" would silently do nothing.
        text.isNotBlank() -> flow {
            emit(ContentState.Loading)
            emit(repository.search(text).asContentState(EmptyReason.NO_RESULTS))
        }

        selectedTab == SearchTab.FAVORITES ->
            repository.observeFavorites().asState(EmptyReason.NOTHING_LOGGED)

        selectedTab == SearchTab.FREQUENT ->
            repository.observeFrequent().asState(EmptyReason.NOTHING_LOGGED)

        // Recents are the default: opening search with nothing typed should
        // offer what you actually eat, not a blank screen.
        else -> repository.observeRecent().asState(EmptyReason.NOTHING_LOGGED)
    }

    private fun kotlinx.coroutines.flow.Flow<List<FoodWithServings>>.asState(
        emptyReason: EmptyReason,
    ) = flow {
        emit(ContentState.Loading)
        collect { emit(it.asContentState(emptyReason)) }
    }

    fun onQueryChange(text: String) {
        query.value = text
    }

    fun onTabChange(selected: SearchTab) {
        tab.value = selected
    }

    fun onClearQuery() {
        query.value = ""
    }

    fun onToggleFavorite(foodId: String) {
        viewModelScope.launch {
            runCatching { repository.toggleFavorite(foodId) }
                .onFailure {
                    message.value = UiMessage("Couldn't update favourite", isError = true)
                }
        }
    }

    /** Clears a consumed snackbar so it isn't shown twice on rotation. */
    fun onMessageShown() {
        message.value = null
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

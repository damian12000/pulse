package com.pulse.feature.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulse.core.data.DiaryRepository
import com.pulse.core.data.FoodRepository
import com.pulse.core.database.entity.FoodServingEntity
import com.pulse.core.domain.Meal
import com.pulse.core.domain.MealTiming
import com.pulse.core.model.FoodNutrition
import com.pulse.core.model.Nutrition
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LogFoodUiState(
    val isLoading: Boolean = true,
    val foodName: String = "",
    val brand: String? = null,
    val servings: List<FoodServingEntity> = emptyList(),
    val selectedServing: FoodServingEntity? = null,
    /** Raw text so the field can hold "2." mid-typing without snapping back. */
    val quantityText: String = "1",
    val meal: Meal = Meal.SNACK,
    /** Live-scaled figures for the current serving × quantity. */
    val nutrition: Nutrition = Nutrition.ZERO,
    val isLoggable: Boolean = false,
    val didLog: Boolean = false,
)

/**
 * Backs the serving/quantity sheet — the last step of logging a food, and the
 * one that decides whether logging feels instant.
 *
 * Everything is pre-filled: default serving, quantity of 1, and the meal
 * inferred from the clock. In the common case the user taps the food and then
 * taps "Add".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LogFoodViewModel @Inject constructor(
    private val foodRepository: FoodRepository,
    private val diaryRepository: DiaryRepository,
) : ViewModel() {

    private val foodId = MutableStateFlow<String?>(null)
    private val selectedServingId = MutableStateFlow<String?>(null)
    private val quantityText = MutableStateFlow("1")
    private val meal = MutableStateFlow(MealTiming.forHour(LocalTime.now().hour))
    private val didLog = MutableStateFlow(false)

    private val food = foodId.flatMapLatest { id ->
        if (id == null) MutableStateFlow(null) else foodRepository.observeFood(id)
    }

    val uiState: StateFlow<LogFoodUiState> =
        combine(food, selectedServingId, quantityText, meal, didLog) { f, servingId, qty, m, logged ->
            if (f == null) return@combine LogFoodUiState(isLoading = true, meal = m)

            val serving = f.servings.firstOrNull { it.id == servingId }
                ?: f.servings.firstOrNull { it.isDefault }
                ?: f.servings.firstOrNull()

            val quantity = qty.toQuantityOrNull()
            val nutrition = if (serving != null && quantity != null) {
                f.food.toFoodNutrition().forServing(serving.gramWeight, quantity)
            } else {
                Nutrition.ZERO
            }

            LogFoodUiState(
                isLoading = false,
                foodName = f.food.name,
                brand = f.food.brand,
                servings = f.servings,
                selectedServing = serving,
                quantityText = qty,
                meal = m,
                nutrition = nutrition,
                // Guard the button rather than the log call: a half-typed "2."
                // or an empty field must not produce a zero-calorie entry.
                isLoggable = serving != null && quantity != null,
                didLog = logged,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = LogFoodUiState(),
        )

    fun setFood(id: String) {
        if (foodId.value == id) return
        foodId.value = id
        // Reset per-food state so reopening the sheet for a different food
        // doesn't inherit the previous one's serving or quantity.
        selectedServingId.value = null
        quantityText.value = "1"
        didLog.value = false
    }

    fun onServingSelected(servingId: String) {
        selectedServingId.value = servingId
    }

    fun onQuantityChange(text: String) {
        // Accept anything typeable; validity is decided by [isLoggable].
        quantityText.value = text.take(MAX_QUANTITY_CHARS)
    }

    fun onMealSelected(selected: Meal) {
        meal.value = selected
    }

    fun log(date: LocalDate = LocalDate.now()) {
        val state = uiState.value
        val serving = state.selectedServing ?: return
        val quantity = state.quantityText.toQuantityOrNull() ?: return
        val id = foodId.value ?: return

        viewModelScope.launch {
            diaryRepository.logFood(
                date = date.toEpochDay(),
                mealType = meal.value.name,
                foodId = id,
                servingId = serving.id,
                quantity = quantity,
            )
            didLog.value = true
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val MAX_QUANTITY_CHARS = 6
    }
}

/**
 * Parses a typed quantity.
 *
 * Accepts a comma decimal separator because a lot of the world types "2,5", and
 * rejects zero, negatives and NaN so an unloggable entry can never be created.
 */
internal fun String.toQuantityOrNull(): Double? {
    val normalized = trim().replace(',', '.')
    if (normalized.isEmpty()) return null
    val value = normalized.toDoubleOrNull() ?: return null
    return value.takeIf { it.isFinite() && it > 0 }
}

/** Scales per-100 nutrition by a serving weight and a quantity. */
private fun FoodNutrition.forServing(gramWeight: Double, quantity: Double): Nutrition =
    forAmount(gramWeight * quantity)

/** Maps the persisted per-100 columns onto the domain type. */
private fun com.pulse.core.database.entity.FoodEntity.toFoodNutrition() = FoodNutrition(
    kcalPer100 = kcalPer100,
    proteinPer100 = proteinPer100,
    carbsPer100 = carbsPer100,
    fatPer100 = fatPer100,
    fiberPer100 = fiberPer100,
    sugarPer100 = sugarPer100,
    satFatPer100 = satFatPer100,
    sodiumMgPer100 = sodiumMgPer100,
    cholesterolMgPer100 = cholesterolMgPer100,
    potassiumMgPer100 = potassiumMgPer100,
)

package com.pulse.feature.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulse.core.data.FoodDraft
import com.pulse.core.data.FoodRepository
import com.pulse.core.model.DataConfidence
import com.pulse.core.model.EnergyCheck
import com.pulse.core.model.FoodNutrition
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Which basis the user is typing nutrition figures in.
 *
 * **Per-serving is the default, and that is the whole point.** A nutrition
 * label states values per serving, so asking for per-100 g would force mental
 * arithmetic on every entry and invite errors. The conversion to the per-100
 * canonical form happens here, not in the user's head.
 */
enum class NutritionBasis { PER_SERVING, PER_100 }

data class CreateFoodUiState(
    val name: String = "",
    val brand: String = "",
    val barcode: String? = null,
    val servingLabel: String = "1 serving",
    val servingGrams: String = "",
    val basis: NutritionBasis = NutritionBasis.PER_SERVING,
    val isLiquid: Boolean = false,

    val kcal: String = "",
    val protein: String = "",
    val carbs: String = "",
    val fat: String = "",
    val fiber: String = "",
    val sugar: String = "",
    val satFat: String = "",
    val sodiumMg: String = "",

    val errors: Map<Field, String> = emptyMap(),
    val energyWarning: String? = null,
    val confidence: DataConfidence? = null,
    val isSaving: Boolean = false,
    val savedFoodId: String? = null,
) {
    val canSave: Boolean
        get() = name.isNotBlank() &&
            servingGrams.toPositiveOrNull() != null &&
            kcal.toNonNegativeOrNull() != null &&
            protein.toNonNegativeOrNull() != null &&
            carbs.toNonNegativeOrNull() != null &&
            fat.toNonNegativeOrNull() != null &&
            !isSaving
}

enum class Field { NAME, SERVING_GRAMS, KCAL, PROTEIN, CARBS, FAT }

@HiltViewModel
class CreateFoodViewModel @Inject constructor(
    private val repository: FoodRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateFoodUiState())
    val uiState: StateFlow<CreateFoodUiState> = _uiState.asStateFlow()

    /** Pre-fills from a failed scan so a dead end becomes a 30-second form. */
    fun prefill(barcode: String?, suggestedName: String?) {
        _uiState.update {
            it.copy(
                barcode = barcode,
                name = suggestedName ?: it.name,
            )
        }
    }

    fun onNameChange(value: String) = update { copy(name = value) }
    fun onBrandChange(value: String) = update { copy(brand = value) }
    fun onServingLabelChange(value: String) = update { copy(servingLabel = value) }
    fun onServingGramsChange(value: String) = update { copy(servingGrams = value) }
    fun onBasisChange(value: NutritionBasis) = update { copy(basis = value) }
    fun onLiquidChange(value: Boolean) = update { copy(isLiquid = value) }

    fun onKcalChange(value: String) = update { copy(kcal = value) }
    fun onProteinChange(value: String) = update { copy(protein = value) }
    fun onCarbsChange(value: String) = update { copy(carbs = value) }
    fun onFatChange(value: String) = update { copy(fat = value) }
    fun onFiberChange(value: String) = update { copy(fiber = value) }
    fun onSugarChange(value: String) = update { copy(sugar = value) }
    fun onSatFatChange(value: String) = update { copy(satFat = value) }
    fun onSodiumChange(value: String) = update { copy(sodiumMg = value) }

    private inline fun update(block: CreateFoodUiState.() -> CreateFoodUiState) {
        _uiState.update { it.block().withDerivedFeedback() }
    }

    /**
     * Live sanity feedback.
     *
     * `4P + 4C + 9F` should land near the stated calories. When it doesn't, the
     * usual cause is a typo — a misplaced decimal or a field entered in the
     * wrong unit. Saying so while they type catches it before it becomes a
     * silently wrong diary entry.
     */
    private fun CreateFoodUiState.withDerivedFeedback(): CreateFoodUiState {
        val k = kcal.toNonNegativeOrNull()
        val p = protein.toNonNegativeOrNull()
        val c = carbs.toNonNegativeOrNull()
        val f = fat.toNonNegativeOrNull()

        if (k == null || p == null || c == null || f == null) {
            return copy(energyWarning = null, confidence = null)
        }

        val expected = EnergyCheck.expectedKcal(p, c, f)
        val nutrition = toPer100(k, p, c, f) ?: return copy(energyWarning = null, confidence = null)
        val level = EnergyCheck.classify(nutrition)

        val warning = when {
            k > 0 && kotlin.math.abs(expected - k) / k > 0.25 ->
                "These macros work out to about ${expected.toInt()} kcal, " +
                    "but you entered ${k.toInt()}. Worth double-checking."
            nutrition.kcalPer100 > EnergyCheck.MAX_PLAUSIBLE_KCAL_PER_100G ->
                "That's more than ${EnergyCheck.MAX_PLAUSIBLE_KCAL_PER_100G.toInt()} kcal per " +
                    "100 ${if (isLiquid) "ml" else "g"}, which isn't physically possible. " +
                    "Check the serving size."
            else -> null
        }

        return copy(energyWarning = warning, confidence = level)
    }

    /** Converts whatever basis the user typed into the canonical per-100 form. */
    private fun CreateFoodUiState.toPer100(
        k: Double,
        p: Double,
        c: Double,
        f: Double,
    ): FoodNutrition? {
        val grams = servingGrams.toPositiveOrNull() ?: return null
        val factor = when (basis) {
            NutritionBasis.PER_100 -> 1.0
            NutritionBasis.PER_SERVING -> 100.0 / grams
        }
        return FoodNutrition(
            kcalPer100 = k * factor,
            proteinPer100 = p * factor,
            carbsPer100 = c * factor,
            fatPer100 = f * factor,
            fiberPer100 = fiber.toNonNegativeOrNull()?.times(factor),
            sugarPer100 = sugar.toNonNegativeOrNull()?.times(factor),
            satFatPer100 = satFat.toNonNegativeOrNull()?.times(factor),
            sodiumMgPer100 = sodiumMg.toNonNegativeOrNull()?.times(factor),
        )
    }

    fun save() {
        val state = _uiState.value
        val errors = validate(state)
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(errors = errors) }
            return
        }

        val nutrition = state.toPer100(
            state.kcal.toNonNegativeOrNull()!!,
            state.protein.toNonNegativeOrNull()!!,
            state.carbs.toNonNegativeOrNull()!!,
            state.fat.toNonNegativeOrNull()!!,
        ) ?: return

        _uiState.update { it.copy(isSaving = true, errors = emptyMap()) }

        viewModelScope.launch {
            val id = repository.createUserFood(
                FoodDraft(
                    name = state.name.trim(),
                    brand = state.brand.trim().takeIf(String::isNotEmpty),
                    barcode = state.barcode,
                    nutrition = nutrition,
                    servingLabel = state.servingLabel.trim().ifEmpty { "1 serving" },
                    servingGrams = state.servingGrams.toPositiveOrNull()!!,
                    isLiquid = state.isLiquid,
                ),
            )
            _uiState.update { it.copy(isSaving = false, savedFoodId = id) }
        }
    }

    private fun validate(state: CreateFoodUiState): Map<Field, String> = buildMap {
        if (state.name.isBlank()) put(Field.NAME, "Give it a name")
        if (state.servingGrams.toPositiveOrNull() == null) {
            put(Field.SERVING_GRAMS, "How many ${if (state.isLiquid) "ml" else "grams"} in a serving?")
        }
        if (state.kcal.toNonNegativeOrNull() == null) put(Field.KCAL, "Calories are required")
        if (state.protein.toNonNegativeOrNull() == null) put(Field.PROTEIN, "Enter 0 if none")
        if (state.carbs.toNonNegativeOrNull() == null) put(Field.CARBS, "Enter 0 if none")
        if (state.fat.toNonNegativeOrNull() == null) put(Field.FAT, "Enter 0 if none")
    }
}

/** Positive, finite, comma-or-dot decimal. */
internal fun String.toPositiveOrNull(): Double? =
    trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }

/**
 * Non-negative and finite. Zero is valid here — plenty of foods genuinely have
 * no fat — but blank is not, so "I didn't fill this in" stays distinguishable
 * from "it's actually zero".
 */
internal fun String.toNonNegativeOrNull(): Double? =
    trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

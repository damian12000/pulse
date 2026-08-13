package com.pulse.feature.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulse.core.domain.Meal
import com.pulse.core.model.Display

/**
 * The serving/quantity sheet — the final step of logging a food.
 *
 * Opens as a modal sheet over whatever screen you were on, so logging never
 * costs you your place (PHASE2_ARCHITECTURE.md §7.1). Everything is pre-filled;
 * in the common case this is one tap.
 *
 * Visual design lands in Phase 7.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogFoodSheet(
    foodId: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LogFoodViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(foodId) { viewModel.setFood(foodId) }

    // Close as soon as the entry is written — the confirmation is the food
    // appearing in the diary, not an extra dialog to dismiss.
    LaunchedEffect(state.didLog) {
        if (state.didLog) onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        if (state.isLoading) {
            Column(
                Modifier.fillMaxWidth().padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            return@ModalBottomSheet
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(
                    text = state.foodName,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                state.brand?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // --- live nutrition -------------------------------------------
            // Updates as the quantity changes, so the number you're committing
            // to is visible before you commit to it.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${Display.kcal(state.nutrition.kcal)} kcal",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = "P ${Display.grams(state.nutrition.proteinG)}g · " +
                        "C ${Display.grams(state.nutrition.carbsG)}g · " +
                        "F ${Display.grams(state.nutrition.fatG)}g",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // --- serving ---------------------------------------------------
            if (state.servings.size > 1) {
                Text("Serving", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.servings, key = { it.id }) { serving ->
                        FilterChip(
                            selected = serving.id == state.selectedServing?.id,
                            onClick = { viewModel.onServingSelected(serving.id) },
                            label = { Text(serving.label) },
                        )
                    }
                }
            }

            // --- quantity --------------------------------------------------
            OutlinedTextField(
                value = state.quantityText,
                onValueChange = viewModel::onQuantityChange,
                label = { Text("Number of servings") },
                singleLine = true,
                isError = !state.isLoggable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            // --- meal ------------------------------------------------------
            // Pre-selected from the clock; visible so a wrong guess is obvious
            // and one tap away from corrected.
            Text("Meal", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Meal.entries.toList(), key = { it.name }) { meal ->
                    FilterChip(
                        selected = meal == state.meal,
                        onClick = { viewModel.onMealSelected(meal) },
                        label = { Text(meal.displayName()) },
                    )
                }
            }

            Button(
                onClick = { viewModel.log() },
                enabled = state.isLoggable,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add to ${state.meal.displayName().lowercase()}")
            }
        }
    }
}

private fun Meal.displayName(): String = when (this) {
    Meal.BREAKFAST -> "Breakfast"
    Meal.LUNCH -> "Lunch"
    Meal.DINNER -> "Dinner"
    Meal.SNACK -> "Snack"
}

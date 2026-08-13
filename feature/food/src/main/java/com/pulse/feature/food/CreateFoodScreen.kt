package com.pulse.feature.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Create a food by hand.
 *
 * This is what turns a failed scan from a dead end into a 30-second form
 * (PHASE1_RESEARCH.md §9.4). It is reached from a scan miss with the barcode
 * pre-filled, so the next scan of the same product resolves instantly.
 *
 * Visual design lands in Phase 7.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateFoodScreen(
    onSaved: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    barcode: String? = null,
    suggestedName: String? = null,
    viewModel: CreateFoodViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(barcode, suggestedName) {
        viewModel.prefill(barcode, suggestedName)
    }

    LaunchedEffect(state.savedFoodId) {
        state.savedFoodId?.let(onSaved)
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Add a food") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.barcode?.let {
                Text(
                    "Barcode $it — scanning this again will find it instantly.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Field(
                value = state.name,
                onChange = viewModel::onNameChange,
                label = "Name",
                error = state.errors[Field.NAME],
            )
            Field(state.brand, viewModel::onBrandChange, "Brand (optional)")

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Liquid (measured in ml)", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = state.isLiquid, onCheckedChange = viewModel::onLiquidChange)
            }

            Text("Serving", style = MaterialTheme.typography.titleSmall)
            Field(state.servingLabel, viewModel::onServingLabelChange, "Serving name, e.g. 1 slice")
            Field(
                value = state.servingGrams,
                onChange = viewModel::onServingGramsChange,
                label = if (state.isLiquid) "Millilitres per serving" else "Grams per serving",
                error = state.errors[Field.SERVING_GRAMS],
                numeric = true,
            )

            Text("Nutrition", style = MaterialTheme.typography.titleSmall)
            // Per-serving is the default because that is how labels are
            // written. Offering the choice avoids forcing mental arithmetic
            // either way.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.basis == NutritionBasis.PER_SERVING,
                    onClick = { viewModel.onBasisChange(NutritionBasis.PER_SERVING) },
                    label = { Text("Per serving") },
                )
                FilterChip(
                    selected = state.basis == NutritionBasis.PER_100,
                    onClick = { viewModel.onBasisChange(NutritionBasis.PER_100) },
                    label = { Text(if (state.isLiquid) "Per 100 ml" else "Per 100 g") },
                )
            }

            Field(state.kcal, viewModel::onKcalChange, "Calories", state.errors[Field.KCAL], true)
            Field(state.protein, viewModel::onProteinChange, "Protein (g)", state.errors[Field.PROTEIN], true)
            Field(state.carbs, viewModel::onCarbsChange, "Carbs (g)", state.errors[Field.CARBS], true)
            Field(state.fat, viewModel::onFatChange, "Fat (g)", state.errors[Field.FAT], true)

            // Live typo-catcher: macros that don't add up to the stated
            // calories almost always mean a misplaced decimal.
            state.energyWarning?.let { warning ->
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Text("Optional", style = MaterialTheme.typography.titleSmall)
            Field(state.fiber, viewModel::onFiberChange, "Fibre (g)", numeric = true)
            Field(state.sugar, viewModel::onSugarChange, "Sugars (g)", numeric = true)
            Field(state.satFat, viewModel::onSatFatChange, "Saturated fat (g)", numeric = true)
            Field(state.sodiumMg, viewModel::onSodiumChange, "Sodium (mg)", numeric = true)

            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isSaving) "Saving…" else "Save food")
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    error: String? = null,
    numeric: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            isError = error != null,
            keyboardOptions = if (numeric) {
                KeyboardOptions(keyboardType = KeyboardType.Decimal)
            } else {
                KeyboardOptions.Default
            },
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
    }
}

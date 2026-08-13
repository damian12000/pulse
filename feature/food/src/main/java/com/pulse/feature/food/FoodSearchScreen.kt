package com.pulse.feature.food

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulse.core.database.dao.FoodWithServings
import com.pulse.core.model.ContentState
import com.pulse.core.model.Display
import com.pulse.core.model.EmptyReason
import com.pulse.core.model.ErrorKind
import kotlin.math.roundToInt

/**
 * Food search.
 *
 * Visual design lands in Phase 7 — this is deliberately plain Material 3. What
 * matters here is behaviour: every [ContentState] branch is handled, and the
 * list shows the four figures you actually decide on (kcal, P/C/F).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodSearchScreen(
    onScanClicked: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FoodSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Logging opens over this screen rather than navigating away, so the
    // search results and query survive the round trip.
    var loggingFoodId by rememberSaveable { mutableStateOf<String?>(null) }

    loggingFoodId?.let { foodId ->
        LogFoodSheet(foodId = foodId, onDismiss = { loggingFoodId = null })
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it.text)
            viewModel.onMessageShown()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Food") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text("Search foods") },
                singleLine = true,
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = viewModel::onClearQuery) {
                            Text("×", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )

            // Tabs are hidden while searching — a query searches everything, so
            // showing "Recent / Frequent" alongside results would be a lie.
            if (state.query.isBlank()) {
                TabRow(selectedTabIndex = state.tab.ordinal - 1) {
                    listOf(
                        SearchTab.RECENT to "Recent",
                        SearchTab.FREQUENT to "Frequent",
                        SearchTab.FAVORITES to "Favourites",
                    ).forEach { (tab, label) ->
                        Tab(
                            selected = state.tab == tab,
                            onClick = { viewModel.onTabChange(tab) },
                            text = { Text(label) },
                        )
                    }
                }
            }

            when (val results = state.results) {
                ContentState.Loading -> CenteredBox { CircularProgressIndicator() }

                is ContentState.Empty -> CenteredBox {
                    Text(
                        text = emptyMessage(results.reason, state.query),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                is ContentState.Error -> CenteredBox {
                    Text(
                        text = errorMessage(results.kind),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                is ContentState.Content -> LazyColumn(Modifier.fillMaxSize()) {
                    items(results.data, key = { it.food.id }) { item ->
                        FoodRow(item = item, onClick = { loggingFoodId = item.food.id })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun FoodRow(item: FoodWithServings, onClick: () -> Unit) {
    val food = item.food
    // Show the figures for the serving the user would actually log, not an
    // abstract per-100 g — "1 slice, 113 kcal" is the decision they're making.
    val serving = item.servings.firstOrNull { it.isDefault } ?: item.servings.firstOrNull()
    val factor = (serving?.gramWeight ?: 100.0) / 100.0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = food.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(food.brand, serving?.label).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "P ${(food.proteinPer100 * factor).roundToInt()}g · " +
                    "C ${(food.carbsPer100 * factor).roundToInt()}g · " +
                    "F ${(food.fatPer100 * factor).roundToInt()}g",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = "${Display.kcal(food.kcalPer100 * factor)} kcal",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

private fun emptyMessage(reason: EmptyReason, query: String): String = when (reason) {
    EmptyReason.NO_QUERY -> "Search for a food to get started"
    EmptyReason.NO_RESULTS ->
        "No matches for \"$query\".\nTry a different spelling, scan a barcode, or add it yourself."
    EmptyReason.NOTHING_LOGGED ->
        "Nothing here yet — foods you log will show up automatically."
    EmptyReason.OFFLINE_NO_CACHE ->
        "You're offline and this hasn't been downloaded yet."
}

private fun errorMessage(kind: ErrorKind): String = when (kind) {
    ErrorKind.NETWORK -> "Couldn't reach the network. Local results still work."
    ErrorKind.DATABASE -> "Couldn't read the food database."
    ErrorKind.MISSING_DATA -> "The food database hasn't finished downloading yet."
    ErrorKind.UNKNOWN -> "Something went wrong."
}

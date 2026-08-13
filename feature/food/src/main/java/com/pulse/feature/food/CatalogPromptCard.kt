package com.pulse.feature.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulse.core.data.CatalogState
import kotlin.math.roundToInt

/**
 * Offers the food catalog download.
 *
 * Deliberately **not** a first-run gate. Search already works without it — over
 * your own foods and remote lookups — so blocking startup on a 67 MB transfer
 * would overstate how necessary it is. The size is on the button, and nothing
 * downloads until asked: on a metered connection that is the user's money.
 *
 * Visual design lands in Phase 7.
 */
@Composable
fun CatalogPromptCard(
    modifier: Modifier = Modifier,
    viewModel: CatalogDownloadViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Nothing to say once the catalog is ready, or if it was dismissed.
    if (!state.shouldPrompt && !state.isBusy) return

    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (val s = state.state) {
                is CatalogState.Downloading -> {
                    Text("Downloading food database", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = progressLabel(s),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // An indeterminate bar when the server sent no length —
                    // better than a fake percentage.
                    if (s.fraction != null) {
                        LinearProgressIndicator(
                            progress = { s.fraction!! },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = viewModel::cancel) { Text("Cancel") }
                }

                CatalogState.Verifying -> {
                    Text("Checking the download", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Verifying and unpacking — almost done.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is CatalogState.Failed -> {
                    Text("Download didn't finish", style = MaterialTheme.typography.titleSmall)
                    Text(s.reason, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.retryable) {
                            // Resumes from the partial file rather than
                            // restarting, so a retry is usually quick.
                            TextButton(onClick = viewModel::download) { Text("Try again") }
                        }
                        TextButton(onClick = viewModel::dismiss) { Text("Not now") }
                    }
                }

                CatalogState.Absent -> {
                    Text("Add the full food database", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "326,759 foods and 313,442 barcodes, stored on your phone so " +
                            "search and scanning work offline.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = viewModel::download) {
                            Text("Download (${state.downloadSizeMb} MB)")
                        }
                        TextButton(onClick = viewModel::dismiss) { Text("Not now") }
                    }
                }

                is CatalogState.Ready -> Unit // handled by the early return
            }
        }
    }
}

private fun progressLabel(state: CatalogState.Downloading): String {
    val readMb = state.bytesRead / 1_048_576.0
    val total = state.totalBytes
    return if (total != null && total > 0) {
        val totalMb = total / 1_048_576.0
        val pct = ((state.fraction ?: 0f) * 100).roundToInt()
        "%.0f of %.0f MB · %d%%".format(readMb, totalMb, pct)
    } else {
        "%.0f MB".format(readMb)
    }
}

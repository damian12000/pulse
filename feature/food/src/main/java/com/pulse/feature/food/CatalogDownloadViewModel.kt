package com.pulse.feature.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulse.core.data.BundledAsset
import com.pulse.core.data.BundledDataManager
import com.pulse.core.data.CatalogState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CatalogUiState(
    val state: CatalogState = CatalogState.Absent,
    /** Hidden for this session once dismissed — never nagged again on the same run. */
    val isDismissed: Boolean = false,
) {
    val downloadSizeMb: Int
        get() = (BundledAsset.FOOD_CATALOG.compressedBytes / 1_048_576).toInt()

    /**
     * The prompt only appears when the catalog is genuinely missing. Search
     * already works without it — against user foods and remote lookups — so
     * this is an offer, not a blocker.
     */
    val shouldPrompt: Boolean
        get() = !isDismissed && (state is CatalogState.Absent || state is CatalogState.Failed)

    val isBusy: Boolean
        get() = state is CatalogState.Downloading || state is CatalogState.Verifying
}

@HiltViewModel
class CatalogDownloadViewModel @Inject constructor(
    private val bundledData: BundledDataManager,
) : ViewModel() {

    private val dismissed = MutableStateFlow(false)

    val uiState: StateFlow<CatalogUiState> =
        combine(bundledData.state, dismissed) { state, isDismissed ->
            CatalogUiState(state = state, isDismissed = isDismissed)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CatalogUiState(),
        )

    init {
        // Opening an already-downloaded catalog is what flips state to Ready,
        // so a returning user never sees the prompt.
        viewModelScope.launch { bundledData.openIfDownloaded() }
    }

    /**
     * Explicit user action, never automatic.
     *
     * 67 MB on a metered connection is the user's money; the size is shown on
     * the button and the download only starts when they say so.
     */
    fun download() = bundledData.startDownload()

    /** Cancellation keeps the partial file, so resuming later costs nothing. */
    fun cancel() = bundledData.cancelDownload()

    fun dismiss() {
        dismissed.value = true
    }
}

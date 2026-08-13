package com.pulse.feature.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulse.core.data.BarcodeResult
import com.pulse.core.data.FoodRepository
import com.pulse.core.data.NutrientField
import com.pulse.core.database.dao.FoodWithServings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the scanner is currently showing.
 *
 * Every outcome leads somewhere the user can act — there is no state that just
 * says "failed" and leaves them stuck (PHASE1_RESEARCH.md §9.4).
 */
sealed interface ScanState {
    /** Camera live, nothing decided yet. */
    data object Scanning : ScanState

    /** A code was read; resolving it locally, then remotely. */
    data class Resolving(val barcode: String) : ScanState

    data class Found(val food: FoodWithServings) : ScanState

    /** Known product with gaps the user should fill before logging. */
    data class Incomplete(val food: FoodWithServings, val missing: Set<NutrientField>) : ScanState

    /** Nothing knows it — offer to create it, barcode pre-filled. */
    data class NotFound(val barcode: String, val suggestedName: String?) : ScanState

    /**
     * Offline, or every source errored. Deliberately distinct from [NotFound]:
     * telling someone to hand-create a product that does exist would pollute
     * their library with a worse duplicate.
     */
    data class Unavailable(val barcode: String) : ScanState

    /** Read something that isn't a retail barcode — keep scanning. */
    data object Unreadable : ScanState
}

data class ScannerUiState(
    val scan: ScanState = ScanState.Scanning,
    val torchEnabled: Boolean = false,
    val hasCameraPermission: Boolean = false,
)

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val repository: FoodRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    /**
     * Called from the camera analyzer. Guards against re-entry so a barcode
     * still in the viewfinder doesn't fire repeated lookups.
     */
    fun onBarcodeDetected(raw: String, online: Boolean = true) {
        if (_uiState.value.scan !is ScanState.Scanning) return

        _uiState.update { it.copy(scan = ScanState.Resolving(raw)) }

        viewModelScope.launch {
            val result = runCatching { repository.resolveBarcode(raw, online) }
                .getOrElse { BarcodeResult.Offline(raw) }

            _uiState.update { it.copy(scan = result.toScanState()) }
        }
    }

    private fun BarcodeResult.toScanState(): ScanState = when (this) {
        is BarcodeResult.Found -> ScanState.Found(food)
        is BarcodeResult.Incomplete -> ScanState.Incomplete(food, missing)
        is BarcodeResult.NotFound -> ScanState.NotFound(barcode, suggestedName)
        is BarcodeResult.Offline -> ScanState.Unavailable(barcode)
        is BarcodeResult.Unreadable -> ScanState.Unreadable
    }

    /** Returns to the live camera, ready for the next product. */
    fun resumeScanning() {
        _uiState.update { it.copy(scan = ScanState.Scanning) }
    }

    fun toggleTorch() {
        _uiState.update { it.copy(torchEnabled = !it.torchEnabled) }
    }

    fun onPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(hasCameraPermission = granted) }
    }
}

package com.pulse.core.data

import android.content.Context
import com.pulse.core.database.FoodCatalogDatabase
import com.pulse.core.network.AssetDownloader
import com.pulse.core.network.DownloadProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * A downloadable data asset, pinned to an exact published artefact.
 *
 * The checksum is of the **compressed** file, because that is what is
 * transferred and therefore what corruption would affect. Pinning to a tag
 * rather than `latest` means a republished dataset can never silently change
 * what an installed app downloads.
 */
data class BundledAsset(
    val fileName: String,
    val url: String,
    val compressedSha256: String,
    val compressedBytes: Long,
) {
    companion object {
        private const val RELEASE =
            "https://github.com/damian12000/pulse/releases/download/data-v1"

        /**
         * 326,759 foods · 313,442 barcodes. Verified published and downloadable
         * over plain HTTPS with no authentication.
         */
        val FOOD_CATALOG = BundledAsset(
            fileName = FoodCatalogDatabase.FILE_NAME,
            url = "$RELEASE/opennutrition.db.gz",
            compressedSha256 =
                "9eb5129f4b48db07032f465e449ef21cfeb6dec05416fa588034e6765790b5e6",
            compressedBytes = 70593247,
        )
    }
}

/** What the UI needs to know about the food catalog. */
sealed interface CatalogState {
    /** Not downloaded. Search falls back to user foods and remote lookups. */
    data object Absent : CatalogState

    data class Downloading(val fraction: Float?, val bytesRead: Long, val totalBytes: Long?) :
        CatalogState

    data object Verifying : CatalogState
    data class Ready(val foodCount: Int) : CatalogState
    data class Failed(val reason: String, val retryable: Boolean) : CatalogState
}

/**
 * Owns the downloaded food catalog: fetching it, opening it, and reporting
 * whether it is usable.
 *
 * The app is fully functional without it — the catalog only widens what search
 * and barcode scanning can find locally. Nothing here blocks startup.
 */
@Singleton
class BundledDataManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloader: AssetDownloader,
) {

    private val _state = MutableStateFlow<CatalogState>(CatalogState.Absent)
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    @Volatile
    private var catalog: FoodCatalogDatabase? = null

    private val catalogFile: File
        get() = File(dataDir(), BundledAsset.FOOD_CATALOG.fileName)

    private fun dataDir(): File = File(context.filesDir, "databases").apply { mkdirs() }

    /** Opens an already-downloaded catalog. Safe to call on every launch. */
    suspend fun openIfDownloaded(): FoodCatalogDatabase? {
        catalog?.let { return it }
        val opened = FoodCatalogDatabase.openIfPresent(context, catalogFile) ?: return null
        return try {
            val count = opened.catalogDao().count()
            catalog = opened
            _state.value = CatalogState.Ready(count)
            opened
        } catch (e: Exception) {
            // A corrupt or half-written file must not take the app down; it is
            // discarded so the next attempt re-downloads cleanly.
            runCatching { opened.close() }
            catalogFile.delete()
            _state.value = CatalogState.Absent
            null
        }
    }

    fun isDownloaded(): Boolean =
        catalogFile.exists() && catalogFile.length() > MIN_PLAUSIBLE_BYTES

    /**
     * Downloads the catalog, reporting progress.
     *
     * Resumable and checksum-verified by [AssetDownloader]; this only maps its
     * progress onto something the UI can render and opens the result.
     */
    fun downloadCatalog(): Flow<CatalogState> = flow {
        val asset = BundledAsset.FOOD_CATALOG

        downloader.download(
            url = asset.url,
            destination = catalogFile,
            expectedSha256 = asset.compressedSha256,
            expectedBytes = asset.compressedBytes,
            gzipped = true,
        ).collect { progress ->
            if (progress is DownloadProgress.Done) {
                // Opening it is what makes it Ready. A file that downloaded and
                // verified but won't open is a failure, not a success — and
                // openIfDownloaded discards it so the retry starts clean.
                val state = if (openIfDownloaded() != null) {
                    _state.value
                } else {
                    CatalogState.Failed("Downloaded file could not be opened", retryable = true)
                }
                _state.value = state
                emit(state)
            } else {
                val state = progress.toCatalogState()
                _state.value = state
                emit(state)
            }
        }
    }

    private fun DownloadProgress.toCatalogState(): CatalogState = when (this) {
        DownloadProgress.Starting -> CatalogState.Downloading(null, 0, null)
        is DownloadProgress.Downloading -> CatalogState.Downloading(fraction, bytesRead, totalBytes)
        DownloadProgress.Verifying -> CatalogState.Verifying
        is DownloadProgress.Done -> CatalogState.Ready(0)
        is DownloadProgress.Failed -> CatalogState.Failed(reason, retryable)
    }

    private companion object {
        const val MIN_PLAUSIBLE_BYTES = 1_000_000L
    }
}

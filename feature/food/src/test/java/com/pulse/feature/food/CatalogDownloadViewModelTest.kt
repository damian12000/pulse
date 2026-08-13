package com.pulse.feature.food

import com.pulse.core.data.BundledAsset
import com.pulse.core.data.CatalogState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt's visibility rules.
 *
 * These matter because the catalog is optional: search works without it, so the
 * card must offer rather than nag, and must never claim the app is broken.
 */
class CatalogUiStateTest {

    @Test
    fun `prompts when the catalog is missing`() {
        val state = CatalogUiState(state = CatalogState.Absent)
        assertTrue(state.shouldPrompt)
        assertTrue(!state.isBusy)
    }

    @Test
    fun `prompts again after a failure so the user can retry`() {
        val state = CatalogUiState(state = CatalogState.Failed("network", retryable = true))
        assertTrue(state.shouldPrompt)
    }

    @Test
    fun `stops prompting once the catalog is ready`() {
        val state = CatalogUiState(state = CatalogState.Ready(foodCount = 326_759))
        assertTrue("a ready catalog has nothing to offer", !state.shouldPrompt)
        assertTrue(!state.isBusy)
    }

    @Test
    fun `stops prompting once dismissed`() {
        val state = CatalogUiState(state = CatalogState.Absent, isDismissed = true)
        assertTrue("'Not now' must be respected", !state.shouldPrompt)
    }

    /**
     * Dismissing must not hide an in-flight download — the user would have no
     * way to see progress or cancel it.
     */
    @Test
    fun `a running download still reports busy even when dismissed`() {
        val state = CatalogUiState(
            state = CatalogState.Downloading(0.5f, 35_000_000, 70_000_000),
            isDismissed = true,
        )
        assertTrue(state.isBusy)
    }

    @Test
    fun `verifying counts as busy`() {
        assertTrue(CatalogUiState(state = CatalogState.Verifying).isBusy)
    }

    @Test
    fun `download size is reported in whole megabytes`() {
        val mb = CatalogUiState().downloadSizeMb
        assertEquals(BundledAsset.FOOD_CATALOG.compressedBytes / 1_048_576, mb.toLong())
        assertTrue("expected roughly 67 MB, got $mb", mb in 60..75)
    }
}

class DownloadProgressDisplayTest {

    @Test
    fun `fraction is computed from bytes when a total is known`() {
        val half = CatalogState.Downloading(0.5f, 35_000_000, 70_000_000)
        assertEquals(0.5f, half.fraction!!, 1e-6f)
    }

    /**
     * A server that sends no Content-Length must produce an indeterminate bar,
     * not a fabricated percentage.
     */
    @Test
    fun `fraction is null when the total is unknown`() {
        val unknown = CatalogState.Downloading(null, 1_000, null)
        assertEquals(null, unknown.fraction)
    }
}

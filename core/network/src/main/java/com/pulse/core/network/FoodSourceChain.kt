package com.pulse.core.network

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tries remote sources in priority order, stopping at the first hit.
 *
 * The ordering matters (PHASE1_RESEARCH.md §6.1): Open Food Facts first because
 * it needs no key, has no practical rate limit for one user, and has the best
 * Canadian barcode coverage. Optional keyed sources follow.
 *
 * Rules that make the chain safe to call from the scanner's hot path:
 *
 * - **A source that fails is skipped, never fatal.** Only if *every* source
 *   fails is the result reported as a failure rather than a genuine miss —
 *   otherwise "not found" would be indistinguishable from "the network is down",
 *   and the user would be told to create a food that already exists.
 * - **Unavailable sources are not consulted.** A missing API key means the
 *   source silently isn't in the chain.
 */
@Singleton
class FoodSourceChain @Inject constructor(
    private val sources: List<FoodDataSource>,
) {

    suspend fun byBarcode(ean13: String): ChainResult {
        val available = sources.filter { it.isAvailable }
        if (available.isEmpty()) return ChainResult.NoSources

        var anyFailed = false
        val failures = mutableListOf<String>()

        for (source in available) {
            when (val result = source.byBarcode(ean13)) {
                is RemoteResult.Found -> return ChainResult.Found(result.food, source.id)
                RemoteResult.NotFound -> Unit // genuine miss; try the next source
                is RemoteResult.Failed -> {
                    anyFailed = true
                    failures += "${source.id}: ${result.reason}"
                }
            }
        }

        // Distinguishing these two is the whole point: a miss means "create it",
        // a failure means "try again later".
        return if (anyFailed && failures.size == available.size) {
            ChainResult.AllFailed(failures)
        } else {
            ChainResult.NotFound
        }
    }
}

sealed interface ChainResult {
    data class Found(val food: RemoteFood, val sourceId: String) : ChainResult

    /** At least one source answered, and none of them knows this product. */
    data object NotFound : ChainResult

    /** Every source errored — this is not evidence the product doesn't exist. */
    data class AllFailed(val reasons: List<String>) : ChainResult

    /** Nothing configured or reachable, e.g. offline with no local sources. */
    data object NoSources : ChainResult
}

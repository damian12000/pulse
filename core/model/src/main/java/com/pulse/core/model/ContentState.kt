package com.pulse.core.model

/**
 * The state of anything a screen displays.
 *
 * Making loading / empty / error / content explicit in the type system means a
 * screen physically cannot forget to handle one — and Phase 7 gets a complete
 * list of the states needing artwork by reading the sealed hierarchy rather
 * than by auditing screens (PHASE2_ARCHITECTURE.md §7.2).
 */
sealed interface ContentState<out T> {

    data object Loading : ContentState<Nothing>

    /**
     * Deliberately not the same as [Content] with an empty list: "no results
     * for 'xyzzy'" and "you haven't logged anything yet" want different
     * artwork and different next actions.
     */
    data class Empty(val reason: EmptyReason) : ContentState<Nothing>

    data class Error(
        val kind: ErrorKind,
        val retryable: Boolean = true,
    ) : ContentState<Nothing>

    data class Content<T>(val data: T) : ContentState<T>

    val isLoading: Boolean get() = this is Loading

    fun dataOrNull(): T? = (this as? Content)?.data
}

enum class EmptyReason {
    /** Nothing typed yet — show recents rather than a blank slate. */
    NO_QUERY,

    /** A real search that matched nothing. */
    NO_RESULTS,

    /** The feature works, the user just hasn't used it yet. */
    NOTHING_LOGGED,

    /** Offline and nothing cached to show. */
    OFFLINE_NO_CACHE,
}

enum class ErrorKind {
    NETWORK,
    DATABASE,
    /** Bundled databases not downloaded yet. */
    MISSING_DATA,
    UNKNOWN,
}

/** Wraps a list, choosing [ContentState.Empty] over an empty [ContentState.Content]. */
fun <T> List<T>.asContentState(emptyReason: EmptyReason): ContentState<List<T>> =
    if (isEmpty()) ContentState.Empty(emptyReason) else ContentState.Content(this)

/** A one-shot message for a snackbar. Consumed once, then cleared. */
data class UiMessage(
    val text: String,
    val actionLabel: String? = null,
    val isError: Boolean = false,
)

package com.nuvio.app.features.airing

/**
 * Display-only search and alphabetical ordering for the hidden-title picker.
 * Do not reorder the persisted list: its sequence is used by account sync.
 */
internal fun <T> filterAndSortHiddenAiringTitles(
    hidden: List<T>,
    query: String,
    nameOf: (T) -> String,
): List<T> {
    val needle = query.trim()
    return hidden.asSequence()
        .filter { needle.isEmpty() || nameOf(it).contains(needle, ignoreCase = true) }
        .sortedWith(compareBy<T> { nameOf(it).trim().lowercase() }.thenBy { nameOf(it).trim() })
        .toList()
}

/** Only restore entries removed from the dialog's original snapshot on Save. */
internal fun <T> hiddenAiringIdsToRestore(
    original: List<T>,
    remaining: List<T>,
    idOf: (T) -> Int,
): List<Int> {
    val kept = remaining.mapTo(mutableSetOf(), idOf)
    return original.map(idOf).filterNot { it in kept }
}

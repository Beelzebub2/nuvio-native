package com.nuvio.app.features.watching.sync

internal const val PROGRESS_SNAPSHOT_PAGE_SIZE = 200

internal data class ProgressSnapshotPage<T>(
    val items: List<T>,
    val nextCursor: String?,
)

/** Collect bounded keyset pages without assuming the server's text collation. */
internal suspend fun <T> collectProgressSnapshotPages(
    pageSize: Int,
    fetchPage: suspend (afterKey: String?, limit: Int) -> ProgressSnapshotPage<T>,
): List<T> {
    require(pageSize in 1..500)
    val all = mutableListOf<T>()
    val seenCursors = mutableSetOf<String>()
    var cursor: String? = null
    do {
        val page = fetchPage(cursor, pageSize)
        check(page.items.size <= pageSize) { "Progress snapshot page exceeded its limit" }
        val next = page.nextCursor
        if (next != null) {
            check(next.isNotBlank() && page.items.isNotEmpty() && seenCursors.add(next)) {
                "Progress snapshot cursor did not advance"
            }
        }
        all.addAll(page.items)
        cursor = next
    } while (cursor != null)
    return all
}

/** Only an unknown RPC is eligible for legacy fallback; never hide auth/network errors. */
internal fun isMissingProgressSnapshotPageRpc(error: Throwable): Boolean {
    val detail = generateSequence(error) { it.cause }
        .take(4)
        .joinToString(separator = " ") { "${it.message.orEmpty()} ${it}" }
    return detail.contains("PGRST202", ignoreCase = true) ||
        (detail.contains("sync_pull_watch_progress_page", ignoreCase = true) &&
            detail.contains("could not find the function", ignoreCase = true))
}

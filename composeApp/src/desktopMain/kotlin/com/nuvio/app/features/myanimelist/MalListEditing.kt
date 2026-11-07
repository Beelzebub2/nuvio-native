package com.nuvio.app.features.myanimelist

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The fields to change on MAL; null leaves that field as it is. */
internal data class MalListPatch(
    val status: MalStatus? = null,
    val score: Int? = null,
    val watchedEpisodes: Int? = null,
    val isRewatching: Boolean? = null,
)

/** Only the fields that differ between [from] and [to], or null when nothing changed. */
internal fun malListPatch(from: MalListStatus, to: MalListStatus): MalListPatch? {
    val patch = MalListPatch(
        status = to.status?.takeIf { it != from.status },
        score = to.score.takeIf { it != from.score },
        watchedEpisodes = to.watchedEpisodes.takeIf { it != from.watchedEpisodes },
        isRewatching = to.isRewatching.takeIf { it != from.isRewatching },
    )
    return patch.takeUnless { it == MalListPatch() }
}

/**
 * Sets the watched-episode count the way MyAnimeList's own list does: the first episode moves a
 * planned title to Watching, and reaching the final episode marks it Completed (ending a rewatch).
 */
internal fun MalListStatus.withWatchedEpisodes(episodes: Int, totalEpisodes: Int): MalListStatus {
    val count = episodes.coerceIn(0, if (totalEpisodes > 0) totalEpisodes else MAX_MAL_EPISODES)
    val finished = totalEpisodes > 0 && count == totalEpisodes && count > watchedEpisodes
    val started = count > 0 && (status == null || status == MalStatus.PLAN_TO_WATCH)
    return when {
        finished -> copy(status = MalStatus.COMPLETED, watchedEpisodes = count, isRewatching = false)
        started -> copy(status = MalStatus.WATCHING, watchedEpisodes = count)
        else -> copy(watchedEpisodes = count)
    }
}

/** Changes the list status; Completed also fills in every episode when the total is known. */
internal fun MalListStatus.withStatus(newStatus: MalStatus, totalEpisodes: Int): MalListStatus {
    if (newStatus == status) return this
    val episodes = if (newStatus == MalStatus.COMPLETED && totalEpisodes > 0) totalEpisodes else watchedEpisodes
    return copy(status = newStatus, watchedEpisodes = episodes, isRewatching = false)
}

internal fun malScoreLabel(score: Int): String = when (score) {
    10 -> "Masterpiece"
    9 -> "Great"
    8 -> "Very good"
    7 -> "Good"
    6 -> "Fine"
    5 -> "Average"
    4 -> "Bad"
    3 -> "Very bad"
    2 -> "Horrible"
    1 -> "Appalling"
    else -> "No score"
}

internal fun malMediaTypeLabel(type: String?): String? = when (type?.lowercase()) {
    null, "", "unknown" -> null
    "tv" -> "TV"
    "tv_special" -> "TV special"
    "ova", "ona", "cm", "pv" -> type.uppercase()
    else -> type.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** Short facts shown under a list entry's title, e.g. "TV · Spring 2016 · 10 eps". */
internal fun malEntryFacts(entry: MalAnimeListEntry): List<String> = buildList {
    malMediaTypeLabel(entry.mediaType)?.let(::add)
    val season = entry.startSeason?.replaceFirstChar { it.uppercase() }
    when {
        season != null && entry.startYear != null -> add("$season ${entry.startYear}")
        entry.startYear != null -> add(entry.startYear.toString())
    }
    if (entry.totalEpisodes > 0) add(if (entry.totalEpisodes == 1) "1 ep" else "${entry.totalEpisodes} eps")
}

/**
 * Holds the visible list and saves edits to MAL. Each change shows immediately; changes to the
 * same title within [debounceMillis] are sent as one request, and a failed save is rolled back.
 * All calls are expected on one thread (the UI thread).
 */
internal class MalListEditor(
    private val scope: CoroutineScope,
    private val send: suspend (id: Int, patch: MalListPatch) -> MalListStatus,
    private val delete: suspend (id: Int) -> Unit,
    private val onFailure: (String) -> Unit = {},
    private val onCommitted: (before: MalListStatus, after: MalListStatus) -> Unit = { _, _ -> },
    private val debounceMillis: Long = 450,
) {
    val entries = mutableStateListOf<MalAnimeListEntry>()
    private val busyIds = mutableStateMapOf<Int, Boolean>()
    private val confirmed = HashMap<Int, MalListStatus>()
    private val desired = HashMap<Int, MalListStatus>()
    private val versions = HashMap<Int, Long>()
    /** Edits whose save has not started yet. Kept even if the timer's scope is cancelled. */
    private val waiting = HashMap<Int, Job>()
    private val lock = Mutex()

    fun isSaving(id: Int): Boolean = busyIds[id] == true
    val hasPendingWork: Boolean get() = busyIds.isNotEmpty()

    /** Shows a page from MAL. Titles with unsaved edits keep the edited values. */
    fun show(page: List<MalAnimeListEntry>, append: Boolean) {
        if (!append) entries.clear()
        val seen = entries.mapTo(HashSet()) { it.anime.id }
        page.forEach { entry ->
            val id = entry.anime.id
            // MAL paging can repeat a title when the list changes between pages.
            if (!seen.add(id)) return@forEach
            if (id !in desired) confirmed[id] = entry.listStatus
            entries += desired[id]?.let { entry.copy(listStatus = it) } ?: entry
        }
    }

    fun edit(id: Int, change: (MalAnimeListEntry) -> MalListStatus) {
        val index = entries.indexOfFirst { it.anime.id == id }
        if (index < 0) return
        val entry = entries[index]
        val next = change(entry)
        if (next == entry.listStatus) return
        confirmed.getOrPut(id) { entry.listStatus }
        entries[index] = entry.copy(listStatus = next)
        desired[id] = next
        versions[id] = (versions[id] ?: 0L) + 1
        busyIds[id] = true
        waiting.remove(id)?.cancel()
        waiting[id] = scope.launch {
            delay(debounceMillis)
            // A newer edit would have cancelled this timer before it got here.
            waiting.remove(id)
            // Once sending starts, finish it even if the screen closes, so MAL and the UI agree.
            withContext(NonCancellable) { commit(id) }
        }
    }

    /** Sends edits still waiting on their timer, e.g. when the tab closes. */
    fun flush(launch: (suspend () -> Unit) -> Unit) {
        waiting.keys.toList().forEach { id ->
            waiting.remove(id)?.cancel()
            launch { commit(id) }
        }
    }

    fun remove(id: Int, onRemoved: () -> Unit = {}) {
        if (entries.none { it.anime.id == id }) return
        waiting.remove(id)?.cancel()
        busyIds[id] = true
        scope.launch {
            withContext(NonCancellable) {
                lock.withLock {
                    try {
                        delete(id)
                        entries.removeAll { it.anime.id == id }
                        desired.remove(id); confirmed.remove(id); versions.remove(id)
                        busyIds.remove(id)
                        onRemoved()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        busyIds.remove(id)
                        onFailure(e.message ?: "Couldn't remove this title from MyAnimeList.")
                    }
                }
                // A removal that failed must not drop an edit it interrupted.
                if (desired.containsKey(id) && entries.any { it.anime.id == id }) commit(id)
            }
        }
    }

    private suspend fun commit(id: Int) = lock.withLock {
        val target = desired[id] ?: return@withLock
        val base = confirmed[id] ?: return@withLock
        val version = versions[id]
        val patch = malListPatch(base, target)
        if (patch == null) {
            settle(id, version, base)
            return@withLock
        }
        try {
            val result = send(id, patch)
            confirmed[id] = result
            settle(id, version, result)
            onCommitted(base, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            settle(id, version, base)
            onFailure(e.message ?: "Couldn't update MyAnimeList.")
        }
    }

    /** Shows [status] unless a newer edit arrived while this one was being sent. */
    private fun settle(id: Int, version: Long?, status: MalListStatus) {
        if (versions[id] != version) return
        desired.remove(id)
        busyIds.remove(id)
        val index = entries.indexOfFirst { it.anime.id == id }
        if (index >= 0) entries[index] = entries[index].copy(listStatus = status)
    }
}

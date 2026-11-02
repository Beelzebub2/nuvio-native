package com.nuvio.app.features.watchlater

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.library.LibraryItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** A durable record that only explicit new Watch Later saves create. */
@Serializable
internal data class WatchLaterDownloadIntent(
    val itemId: String,
    val itemType: String,
    val bookmarkKey: String,
    val savedAtEpochMs: Long,
) {
    fun matches(item: LibraryItem): Boolean =
        item.id == itemId && item.type == itemType && item.savedAtEpochMs == savedAtEpochMs &&
            (item.watchLaterKey() == bookmarkKey ||
                (bookmarkKey.endsWith("|") && item.watchLaterEpisode != null))
}

/** Saves before publishing changes; a failed write must not lose a pending transfer. */
internal class WatchLaterDownloadIntentStore(
    private val load: (Int) -> String?,
    private val save: (Int, String) -> Unit,
) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    fun items(profile: Int): List<WatchLaterDownloadIntent> = synchronized(lock) {
        val payload = load(profile)
        if (payload.isNullOrBlank()) emptyList() else json.decodeFromString(payload)
    }

    fun add(profile: Int, item: LibraryItem) = synchronized(lock) {
        val newIntent = WatchLaterDownloadIntent(item.id, item.type, item.watchLaterKey(), item.savedAtEpochMs)
        val current = items(profile)
        val updated = current.filterNot { it.itemId == item.id && it.itemType == item.type &&
            it.bookmarkKey == newIntent.bookmarkKey } + newIntent
        if (updated != current) save(profile, json.encodeToString(updated))
    }

    fun remove(profile: Int, intent: WatchLaterDownloadIntent) = synchronized(lock) {
        val current = items(profile)
        val updated = current - intent
        if (updated.size != current.size) save(profile, json.encodeToString(updated))
    }

    fun remove(profile: Int, item: LibraryItem) = synchronized(lock) {
        val current = items(profile)
        val updated = current.filterNot { it.matches(item) }
        if (updated.size != current.size) save(profile, json.encodeToString(updated))
    }

    fun clear(profile: Int) = synchronized(lock) {
        if (items(profile).isNotEmpty()) save(profile, "[]")
    }
}

internal object WatchLaterDownloadIntents {
    private val storage = DesktopStorage.store("nuvio_watch_later_download_intents")
    private val data = WatchLaterDownloadIntentStore(
        load = { storage.getString("items_$it") },
        save = { profile, payload -> storage.putString("items_$profile", payload) },
    )
    fun items(profile: Int) = data.items(profile)
    fun add(profile: Int, item: LibraryItem) = data.add(profile, item)
    fun remove(profile: Int, item: LibraryItem) = data.remove(profile, item)
    fun remove(profile: Int, intent: WatchLaterDownloadIntent) = data.remove(profile, intent)
    fun clear(profile: Int) = data.clear(profile)
}

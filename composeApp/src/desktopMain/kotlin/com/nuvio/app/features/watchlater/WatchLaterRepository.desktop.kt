package com.nuvio.app.features.watchlater

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal actual object WatchLaterRepository {
    private val storage = DesktopStorage.store("nuvio_watch_later")
    private val list = WatchLaterList(
        profileId = { ProfileRepository.activeProfileId },
        load = { storage.getString("items_$it") },
        save = { profile, payload -> storage.putString("items_$profile", payload) },
    )
    actual val items: StateFlow<List<LibraryItem>> get() = list.items
    actual fun ensureLoaded() = list.ensureLoaded()
    actual fun clearLocalState() = list.clearLocalState()
    actual fun toggle(item: MetaPreview) = list.toggle(item)
}

/** A separate local list: middle-click never modifies a connected tracking library. */
internal class WatchLaterList(
    private val profileId: () -> Int,
    private val load: (Int) -> String?,
    private val save: (Int, String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var loadedProfile: Int? = null
    private val state = MutableStateFlow<List<LibraryItem>>(emptyList())
    val items: StateFlow<List<LibraryItem>> = state.asStateFlow()

    @Synchronized
    fun ensureLoaded() {
        val profile = profileId()
        if (profile == loadedProfile) return
        val payload = load(profile)
        val loaded = if (payload.isNullOrBlank()) emptyList() else json.decodeFromString<List<LibraryItem>>(payload)
        state.value = loaded.distinctBy { it.type to it.id }.sortedByDescending { it.savedAtEpochMs }
        loadedProfile = profile
    }

    @Synchronized
    fun clearLocalState() {
        loadedProfile = null
        state.value = emptyList()
    }

    @Synchronized
    fun toggle(item: MetaPreview) {
        ensureLoaded()
        val current = state.value
        val remaining = current.filterNot { it.id == item.id && it.type == item.type }
        val updated = if (remaining.size == current.size) {
            listOf(item.toLibraryItem(savedAtEpochMs = now())) + remaining
        } else remaining
        // Publish only after successful persistence so a failed write cannot look saved.
        save(requireNotNull(loadedProfile), json.encodeToString(updated))
        state.value = updated
    }
}

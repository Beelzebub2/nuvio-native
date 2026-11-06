package com.nuvio.app.features.watchlater

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal actual object PersonalMediaRepository {
    private val storage = DesktopStorage.store("nuvio_personal_media")
    private val list = PersonalMediaList({ ProfileRepository.activeProfileId },
        { storage.getString("items_$it") }, { p, data -> storage.putString("items_$p", data) })
    actual val items get() = list.items
    actual fun ensureLoaded() = list.ensureLoaded()
    actual fun clearLocalState() = list.clearLocalState()
    actual fun setStatus(item: MetaPreview, status: PersonalMediaStatus?) = list.setStatus(item, status)
    actual fun setRating(item: MetaPreview, rating: Int?) = list.setRating(item, rating)
    actual fun exportLocalImportSnapshot(): String? {
        list.ensureLoaded()
        return Json.encodeToString(list.items.value)
    }
    actual fun importAnonymousItems(payload: String) = list.importMissing(payload)
}

internal class PersonalMediaList(
    private val profileId: () -> Int, private val load: (Int) -> String?, private val save: (Int, String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var profile: Int? = null
    private val state = MutableStateFlow<List<PersonalMediaEntry>>(emptyList())
    val items: StateFlow<List<PersonalMediaEntry>> = state.asStateFlow()
    @Synchronized fun ensureLoaded() {
        val current = profileId()
        if (profile == current) return
        state.value = load(current)?.takeIf { it.isNotBlank() }?.let { json.decodeFromString<List<PersonalMediaEntry>>(it) }
            .orEmpty().distinctBy { it.item.type to it.item.id }
        profile = current
    }
    @Synchronized fun clearLocalState() { profile = null; state.value = emptyList() }
    @Synchronized fun importMissing(payload: String) {
        val guest = json.decodeFromString<List<PersonalMediaEntry>>(payload)
        require(guest.all { it.item.id.isNotBlank() && (it.rating == null || it.rating in 1..10) }) {
            "Saved personal media entries are invalid"
        }
        ensureLoaded()
        val original = state.value
        val existing = original.mapTo(mutableSetOf()) { it.item.type to it.item.id }
        val additions = guest.filter { existing.add(it.item.type to it.item.id) }
        if (additions.isEmpty()) return
        val merged = original + additions
        save(requireNotNull(profile), json.encodeToString(merged))
        state.value = merged
    }
    @Synchronized fun setStatus(item: MetaPreview, status: PersonalMediaStatus?) = update(item) { it.copy(status = status) }
    @Synchronized fun setRating(item: MetaPreview, rating: Int?) {
        require(rating == null || rating in 1..10) { "Rating must be between 1 and 10" }
        update(item) { it.copy(rating = rating) }
    }
    private fun update(item: MetaPreview, change: (PersonalMediaEntry) -> PersonalMediaEntry) {
        ensureLoaded()
        val old = state.value.firstOrNull { it.item.id == item.id && it.item.type == item.type }
            ?: PersonalMediaEntry(item.copy(watchLaterEpisode = null).toLibraryItem(now()))
        val updated = change(old.copy(item = item.copy(watchLaterEpisode = null).toLibraryItem(now())))
        val others = state.value.filterNot { it.item.id == item.id && it.item.type == item.type }
        val result = if (updated.status == null && updated.rating == null) others else listOf(updated) + others
        save(requireNotNull(profile), json.encodeToString(result))
        state.value = result
    }
}

package com.nuvio.app.features.watchlater

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryItem
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
internal enum class PersonalMediaStatus(val label: String) {
    WATCHING("Watching"), COMPLETED("Completed"), ON_HOLD("On Hold"), DROPPED("Dropped"), PLAN_TO_WATCH("Plan to Watch"),
}

@Serializable
internal data class PersonalMediaEntry(val item: LibraryItem, val status: PersonalMediaStatus? = null, val rating: Int? = null)

internal expect object PersonalMediaRepository {
    val items: StateFlow<List<PersonalMediaEntry>>
    fun ensureLoaded()
    fun clearLocalState()
    fun setStatus(item: MetaPreview, status: PersonalMediaStatus?)
    fun setRating(item: MetaPreview, rating: Int?)
    fun exportLocalImportSnapshot(): String?
    fun importAnonymousItems(payload: String)
}

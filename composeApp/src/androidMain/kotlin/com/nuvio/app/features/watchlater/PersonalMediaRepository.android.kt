package com.nuvio.app.features.watchlater
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
internal actual object PersonalMediaRepository {
    actual val items: StateFlow<List<PersonalMediaEntry>> = MutableStateFlow(emptyList())
    actual fun ensureLoaded() = Unit
    actual fun clearLocalState() = Unit
    actual fun setStatus(item: MetaPreview, status: PersonalMediaStatus?) = Unit
    actual fun setRating(item: MetaPreview, rating: Int?) = Unit
}

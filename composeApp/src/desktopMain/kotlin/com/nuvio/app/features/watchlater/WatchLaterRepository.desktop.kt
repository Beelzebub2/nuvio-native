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
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.seriesPrimaryAction
import com.nuvio.app.features.library.toMetaPreview
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watched.WatchedRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal actual object WatchLaterRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutation = Mutex()
    private val storage = DesktopStorage.store("nuvio_watch_later")
    private val list = WatchLaterList(
        profileId = { ProfileRepository.activeProfileId },
        load = { storage.getString("items_$it") },
        save = { profile, payload ->
            try {
                storage.putString("items_$profile", payload)
            } catch (error: Exception) {
                // A failed write must not let the storage cache suppress the next retry.
                storage.clearInMemory()
                throw error
            }
        },
    )
    actual val items: StateFlow<List<LibraryItem>> get() = list.items
    actual fun ensureLoaded() = list.ensureLoaded()
    actual fun clearLocalState() { scope.coroutineContext.cancelChildren(); list.clearLocalState() }
    actual fun toggle(item: MetaPreview) {
        val profile = ProfileRepository.activeProfileId
        scope.launch {
            mutation.withLock {
                try {
                    val resolved = resolveEpisode(item)
                    currentCoroutineContext().ensureActive()
                    if (ProfileRepository.activeProfileId != profile) return@withLock
                    list.toggle(resolved)
                    val saved = items.value.any { it.watchLaterKey() == resolved.toLibraryItem(0L).watchLaterKey() }
                    val label = resolved.watchLaterEpisode?.let { "${resolved.name} · E${it.episodeNumber ?: "?"}" } ?: resolved.name
                    NuvioToastController.show(if (saved) "$label added to Watch Later" else "$label removed from Watch Later")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { NuvioToastController.show("Couldn't update Watch Later: ${e.message}") }
            }
        }
    }

    actual fun remove(item: LibraryItem) = list.remove(item)
    actual fun removeCompletedEpisode(session: WatchProgressPlaybackSession) = list.removeCompletedEpisode(session)
    actual fun resolveEpisodes() {
        val profile = ProfileRepository.activeProfileId
        scope.launch {
            mutation.withLock {
                list.ensureLoaded()
                for (item in list.items.value.filter { it.type == "series" && it.watchLaterEpisode == null }) {
                    val resolved = try { resolveEpisode(item.toMetaPreview()) }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
                    currentCoroutineContext().ensureActive()
                    if (ProfileRepository.activeProfileId != profile) return@withLock
                    if (resolved.watchLaterEpisode != null) list.replace(item, resolved.toLibraryItem(item.savedAtEpochMs))
                }
            }
        }
    }

    private suspend fun resolveEpisode(item: MetaPreview): MetaPreview {
        if (item.type != "series") return item
        item.watchLaterEpisode?.let { episode ->
            if (!episode.videoId.startsWith(com.nuvio.app.features.airing.NATIVE_AIRING_PREFIX)) return item
            val cached = MetaDetailsRepository.peek(item.type, item.id)?.videos?.firstOrNull { it.season == episode.seasonNumber && it.episode == episode.episodeNumber }
            val resolved = cached ?: withTimeoutOrNull(8_000L) { MetaDetailsRepository.fetch(item.type, item.id)?.videos
                ?.firstOrNull { it.season == episode.seasonNumber && it.episode == episode.episodeNumber } }
            return if (resolved != null) item.copy(watchLaterEpisode = resolved.toWatchLaterEpisode()) else item
        }
        WatchProgressRepository.ensureLoaded()
        WatchedRepository.ensureLoaded()
        WatchProgressRepository.uiState.value.entries.filter { it.parentMetaId == item.id &&
            it.parentMetaType == item.type && it.isEpisode && !it.isEffectivelyCompleted }
            .maxByOrNull { it.lastUpdatedEpochMs }?.let { progress ->
                return item.copy(watchLaterEpisode = WatchLaterEpisode(progress.videoId,
                    progress.episodeTitle ?: "Episode ${progress.episodeNumber}", progress.seasonNumber,
                    progress.episodeNumber, progress.episodeThumbnail))
            }
        val meta = withTimeoutOrNull(15_000L) { MetaDetailsRepository.fetch(item.type, item.id) } ?: return item
        val action = meta.seriesPrimaryAction(
            entries = WatchProgressRepository.uiState.value.entries,
            watchedItems = WatchedRepository.uiState.value.items,
            todayIsoDate = CurrentDateProvider.todayIsoDate(), allowRewatch = true,
            watchedKeys = WatchedRepository.uiState.value.watchedKeys,
        ) ?: return item
        val video = meta.videos.firstOrNull { it.id == action.videoId ||
            (it.season == action.seasonNumber && it.episode == action.episodeNumber) } ?: return item
        return item.copy(watchLaterEpisode = video.toWatchLaterEpisode())
    }
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
        state.value = loadItems(profile)
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
        val key = item.toLibraryItem(0L).watchLaterKey()
        val remaining = current.filterNot { it.watchLaterKey() == key }
        val updated = if (remaining.size == current.size) {
            listOf(item.toLibraryItem(savedAtEpochMs = now())) + remaining
        } else remaining
        // Publish only after successful persistence so a failed write cannot look saved.
        save(requireNotNull(loadedProfile), json.encodeToString(updated))
        state.value = updated
    }

    @Synchronized fun remove(item: LibraryItem) {
        ensureLoaded()
        persist(state.value.filterNot { it.watchLaterKey() == item.watchLaterKey() })
    }

    @Synchronized fun removeCompletedEpisode(session: WatchProgressPlaybackSession) {
        val profile = session.profileId
        val current = if (loadedProfile == profile) state.value else loadItems(profile)
        val updated = current.filterNot { item ->
            val episode = item.watchLaterEpisode
            item.id == session.parentMetaId && item.type == session.parentMetaType && episode != null &&
                (episode.seasonNumber == null || session.seasonNumber == null || episode.seasonNumber == session.seasonNumber) &&
                (episode.episodeNumber == null || session.episodeNumber == null || episode.episodeNumber == session.episodeNumber) &&
                if (episode.seasonNumber != null && episode.episodeNumber != null &&
                    session.seasonNumber != null && session.episodeNumber != null) {
                    true
                } else episode.videoId.isNotBlank() && episode.videoId == session.videoId
        }
        if (updated.size == current.size) return
        save(profile, json.encodeToString(updated))
        // A final player flush may arrive after the user switched profiles.
        if (loadedProfile == profile) {
            if (profileId() == profile) state.value = updated else loadedProfile = null
        }
    }

    @Synchronized fun replace(old: LibraryItem, replacement: LibraryItem) {
        ensureLoaded()
        persist(state.value.map { if (it.watchLaterKey() == old.watchLaterKey()) replacement else it }
            .distinctBy { it.watchLaterKey() })
    }

    private fun persist(updated: List<LibraryItem>) {
        save(requireNotNull(loadedProfile), json.encodeToString(updated))
        state.value = updated
    }

    private fun loadItems(profile: Int): List<LibraryItem> {
        val payload = load(profile)
        val loaded = if (payload.isNullOrBlank()) emptyList() else json.decodeFromString<List<LibraryItem>>(payload)
        return loaded.distinctBy { it.watchLaterKey() }.sortedByDescending { it.savedAtEpochMs }
    }
}

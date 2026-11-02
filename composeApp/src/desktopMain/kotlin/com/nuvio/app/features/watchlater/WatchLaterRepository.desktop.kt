package com.nuvio.app.features.watchlater

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.downloads.DownloadDesktopFeatures
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.airing.NATIVE_AIRING_PREFIX
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
    private var pendingJob: Job? = null
    private var pendingProfile: Int? = null
    private const val PendingRetryDelayMs = 30 * 60_000L
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
    actual fun ensureLoaded() {
        list.ensureLoaded()
        schedulePendingDownloads(ProfileRepository.activeProfileId)
    }
    actual fun clearLocalState() {
        scope.coroutineContext.cancelChildren()
        synchronized(this) { pendingJob = null; pendingProfile = null }
        list.clearLocalState()
    }
    actual fun toggle(item: MetaPreview) {
        val profile = ProfileRepository.activeProfileId
        scope.launch {
            mutation.withLock {
                try {
                    val resolved = resolveEpisode(item)
                    currentCoroutineContext().ensureActive()
                    if (ProfileRepository.activeProfileId != profile) return@withLock
                    list.ensureLoaded()
                    val targetKey = resolved.toLibraryItem(0L).watchLaterKey()
                    val previous = items.value.firstOrNull { it.watchLaterKey() == targetKey }
                    val autoDownloadEnabled = DownloadDesktopFeatures.autoDownloadWatchLater(profile)
                    list.toggle(resolved, beforeAdd = { added ->
                        // Write the pending intent first. If saving the bookmark fails, the
                        // orphan is discarded at reconciliation without losing a real request.
                        if (autoDownloadEnabled) WatchLaterDownloadIntents.add(profile, added)
                    })
                    val savedItem = items.value.firstOrNull { it.watchLaterKey() == targetKey }
                    val saved = savedItem != null
                    val label = resolved.watchLaterEpisode?.let { "${resolved.name} · E${it.episodeNumber ?: "?"}" } ?: resolved.name
                    NuvioToastController.show(if (saved) "$label added to Watch Later" else "$label removed from Watch Later")
                    try {
                        if (savedItem != null && autoDownloadEnabled) {
                            schedulePendingDownloads(profile, restart = true)
                        } else if (previous != null) {
                            WatchLaterDownloadIntents.remove(profile, previous)
                        }
                    } catch (error: Exception) {
                        NuvioToastController.show("Watch Later saved, but couldn't update automatic downloads: ${error.message}")
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { NuvioToastController.show("Couldn't update Watch Later: ${e.message}") }
            }
        }
    }

    actual fun remove(item: LibraryItem) {
        list.remove(item)
        WatchLaterDownloadIntents.remove(ProfileRepository.activeProfileId, item)
    }
    actual fun removeCompletedEpisode(session: WatchProgressPlaybackSession) {
        val before = if (ProfileRepository.activeProfileId == session.profileId) {
            list.ensureLoaded(); list.items.value
        } else emptyList()
        list.removeCompletedEpisode(session)
        if (ProfileRepository.activeProfileId == session.profileId) {
            val remaining = list.items.value.map { it.watchLaterKey() }.toSet()
            before.filterNot { it.watchLaterKey() in remaining }.forEach {
                WatchLaterDownloadIntents.remove(session.profileId, it)
            }
        }
    }
    actual fun exportLocalImportSnapshot(): String? {
        list.ensureLoaded()
        return Json.encodeToString(list.items.value)
    }
    actual fun importAnonymousItems(payload: String) {
        list.importMissing(payload)
    }
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
            schedulePendingDownloads(profile, restart = true)
        }
    }

    internal fun onAutoDownloadPreferenceChanged(profile: Int, enabled: Boolean) {
        if (enabled) {
            if (ProfileRepository.activeProfileId == profile) {
                list.ensureLoaded()
                schedulePendingDownloads(profile, restart = true)
            }
        } else {
            synchronized(this) {
                if (pendingProfile == profile) {
                    pendingJob?.cancel()
                    pendingJob = null
                }
            }
            runCatching { WatchLaterDownloadIntents.clear(profile) }.onFailure { error ->
                NuvioToastController.show("Automatic downloads disabled, but pending requests couldn't be cleared: ${error.message}")
            }
        }
    }

    @Synchronized
    private fun schedulePendingDownloads(profile: Int, restart: Boolean = false) {
        if (!restart && pendingProfile == profile && pendingJob?.isActive == true) return
        pendingJob?.cancel()
        pendingJob = null
        pendingProfile = profile
        if (ProfileRepository.activeProfileId != profile || !DownloadDesktopFeatures.autoDownloadWatchLater(profile)) return
        val hasPending = try {
            WatchLaterDownloadIntents.items(profile).isNotEmpty()
        } catch (error: Exception) {
            NuvioToastController.show("Couldn't read pending Watch Later downloads: ${error.message}")
            false
        }
        if (!hasPending) return
        pendingJob = scope.launch {
            // Loading Watch Later can precede downloads initialization (for example when
            // the user never opens Downloads). Initialize its profile off the UI thread
            // rather than waiting forever for another screen to do it.
            if (ProfileRepository.activeProfileId != profile) return@launch
            DownloadsRepository.ensureLoaded()
            DownloadsRepository.uiState.first {
                it.profileId == profile || ProfileRepository.activeProfileId != profile
            }
            if (ProfileRepository.activeProfileId != profile) return@launch
            AddonRepository.initialize()
            if (ProfileRepository.activeProfileId != profile) return@launch
            while (isActive && ProfileRepository.activeProfileId == profile &&
                DownloadDesktopFeatures.autoDownloadWatchLater(profile)) {
                // Do not permanently fail an automatic download while its only stream addon
                // is still obtaining a manifest after startup/profile switching.
                val addonsReady = withTimeoutOrNull(30_000L) {
                    AddonRepository.uiState.first { state ->
                        state.addons.enabledAddons().none { it.manifest == null && it.isRefreshing }
                    }
                } != null
                if (!addonsReady) {
                    delay(PendingRetryDelayMs)
                    continue
                }
                val pending = try { WatchLaterDownloadIntents.items(profile) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        NuvioToastController.show("Couldn't read pending Watch Later downloads: ${error.message}")
                        break
                    }
                for (intent in pending) {
                    currentCoroutineContext().ensureActive()
                    if (ProfileRepository.activeProfileId != profile ||
                        !DownloadDesktopFeatures.autoDownloadWatchLater(profile)) return@launch
                    val saved = mutation.withLock { list.items.value.firstOrNull(intent::matches) }
                    if (saved == null) {
                        WatchLaterDownloadIntents.remove(profile, intent)
                        continue
                    }
                    try {
                        val resolved = if (saved.type == "series" &&
                            (saved.watchLaterEpisode == null || saved.watchLaterEpisode.videoId.startsWith(NATIVE_AIRING_PREFIX))) {
                            resolveEpisode(saved.toMetaPreview(), forceRefresh = true).toLibraryItem(saved.savedAtEpochMs)
                        } else saved
                        val outcome = queueWatchLaterDownload(resolved, profile, stillEligible = {
                            ProfileRepository.activeProfileId == profile &&
                                DownloadDesktopFeatures.autoDownloadWatchLater(profile) &&
                                WatchLaterDownloadIntents.items(profile).contains(intent) &&
                                list.items.value.any(intent::matches)
                        }, refreshMetadata = true)
                        if (outcome != WatchLaterAutoQueueResult.NotAvailable) {
                            WatchLaterDownloadIntents.remove(profile, intent)
                            if (outcome == WatchLaterAutoQueueResult.Queued) {
                                NuvioToastController.show("${saved.name} queued for download")
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        NuvioToastController.show("Couldn't prepare automatic download for ${saved.name}: ${error.message}")
                    }
                }
                if (WatchLaterDownloadIntents.items(profile).isEmpty()) break
                delay(PendingRetryDelayMs)
            }
        }
    }

    private suspend fun resolveEpisode(item: MetaPreview, forceRefresh: Boolean = false): MetaPreview {
        if (item.type != "series") return item
        item.watchLaterEpisode?.let { episode ->
            if (!episode.videoId.startsWith(com.nuvio.app.features.airing.NATIVE_AIRING_PREFIX)) return item
            val cached = if (forceRefresh) null else MetaDetailsRepository.peek(item.type, item.id)?.videos
                ?.firstOrNull { it.season == episode.seasonNumber && it.episode == episode.episodeNumber }
            val resolved = cached ?: withTimeoutOrNull(8_000L) {
                MetaDetailsRepository.fetch(item.type, item.id, forceRefresh = forceRefresh)?.videos
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
        val meta = withTimeoutOrNull(15_000L) {
            MetaDetailsRepository.fetch(item.type, item.id, forceRefresh = forceRefresh)
        } ?: return item
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
    fun toggle(item: MetaPreview, beforeAdd: (LibraryItem) -> Unit = {}) {
        ensureLoaded()
        val current = state.value
        val key = item.toLibraryItem(0L).watchLaterKey()
        val remaining = current.filterNot { it.watchLaterKey() == key }
        val updated = if (remaining.size == current.size) {
            val added = item.toLibraryItem(savedAtEpochMs = now())
            beforeAdd(added)
            listOf(added) + remaining
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

    /** Keep account Watch Later entries first; do not duplicate an imported episode. */
    @Synchronized fun importMissing(payload: String) {
        val guest = json.decodeFromString<List<LibraryItem>>(payload)
        ensureLoaded()
        val account = state.value
        val existingKeys = account.mapTo(mutableSetOf()) { it.watchLaterKey() }
        val additions = guest.filter { it.id.isNotBlank() && existingKeys.add(it.watchLaterKey()) }
        if (additions.isNotEmpty()) {
            persist((account + additions).sortedByDescending { it.savedAtEpochMs })
        }
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

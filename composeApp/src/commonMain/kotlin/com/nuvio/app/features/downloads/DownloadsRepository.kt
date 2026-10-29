package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.addonSubtitleRequests
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

import com.nuvio.app.isDesktop
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

object DownloadsRepository {
    private const val MaxMobileDownloadAttempts = 3
    private val lock = SynchronizedObject()
    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()
    private val _hasUnseenCompleted = MutableStateFlow(false)
    val hasUnseenCompleted: StateFlow<Boolean> = _hasUnseenCompleted.asStateFlow()
    private val activeHandles = mutableMapOf<String, DownloadsTaskHandle>()
    private val tokens = mutableMapOf<String, Long>()
    private var generation = 0L
    private var ordinal = 0L
    private var hasLoaded = false
    private var loadedProfile: Int? = null

    fun ensureLoaded() = synchronized(lock) { if (!hasLoaded) loadFromDisk() }
    fun onProfileChanged() = synchronized(lock) { loadFromDisk() }
    fun markCompletedSeen() { _hasUnseenCompleted.value = false }
    fun clearLocalState() = synchronized(lock) {
        invalidateTransfers()
        hasLoaded = false
        _hasUnseenCompleted.value = false
        _uiState.value = DownloadsUiState()
        notifyLiveStatusPlatform()
    }

    fun findPlayableDownloadByVideoId(videoId: String?): DownloadItem? = synchronized(lock) {
        ensureLoaded()
        videoId?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
            _uiState.value.items.firstOrNull { it.videoId == id && it.hasPlayableLocalFile() }
        }
    }

    fun findPlayableDownload(parentMetaId: String, seasonNumber: Int? = null,
        episodeNumber: Int? = null, videoId: String? = null): DownloadItem? = synchronized(lock) {
        ensureLoaded()
        // A video ID can be reused by different catalogs. Never cross parent/episode boundaries.
        val candidates = _uiState.value.items.filter { it.parentMetaId == parentMetaId.trim() && it.hasPlayableLocalFile() }
        if (seasonNumber != null && episodeNumber != null) {
            return candidates.firstOrNull { it.seasonNumber == seasonNumber && it.episodeNumber == episodeNumber }
        }
        val normalizedVideoId = videoId?.trim().orEmpty()
        candidates.firstOrNull { normalizedVideoId.isNotEmpty() && it.videoId == normalizedVideoId }
            ?: candidates.firstOrNull { it.seasonNumber == null && it.episodeNumber == null }
    }

    fun playableLocalFileUri(item: DownloadItem): String? = synchronized(lock) {
        ensureLoaded()
        if (item.status != DownloadStatus.Completed) return null
        DownloadsPlatformDownloader.resolveLocalFileUri(item.localFileUri, item.fileName)
    }

    fun enqueueFromStream(contentType: String, videoId: String, parentMetaId: String, parentMetaType: String,
        title: String, logo: String?, poster: String?, background: String?, seasonNumber: Int?, episodeNumber: Int?,
        episodeTitle: String?, episodeThumbnail: String?, stream: StreamItem): DownloadEnqueueResult = synchronized(lock) {
        ensureLoaded()
        val direct = stream.playableDirectUrl?.trim()
        val torrent = if (isDesktop && direct == null) stream.p2pInfoHash else null
        if (direct == null && torrent == null) return DownloadEnqueueResult.MissingUrl
        if (direct != null && !direct.isSupportedDownloadUrl()) return DownloadEnqueueResult.UnsupportedFormat
        val item = newItem(contentType, videoId, parentMetaId, parentMetaType, title, logo, poster, background,
            seasonNumber, episodeNumber, episodeTitle, episodeThumbnail, direct.orEmpty(), stream.streamLabel,
            stream.addonName, stream.addonId).copy(
                streamSubtitle = stream.streamSubtitle,
                sourceHeaders = sanitizeRequestHeaders(stream.behaviorHints.proxyHeaders?.request),
                sourceResponseHeaders = sanitizeResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
                sourceSubtitles = stream.externalSubtitles,
                torrentHash = torrent, torrentFileIdx = stream.p2pFileIdx,
                torrentFilename = stream.behaviorHints.filename, torrentTrackers = stream.p2pTrackers,
            )
        val existing = _uiState.value.items.firstOrNull { it.logicalContentKey == item.logicalContentKey }
        // A manually picked source replaces a failed or still-automatic task. Desktop keeps finished
        // or manually chosen files, since they may live in a folder the user manages.
        val replace = existing != null && (!isDesktop || existing.status == DownloadStatus.Failed ||
            (existing.sourceSelection != null && existing.status != DownloadStatus.Completed))
        if (existing != null && replace) removeItem(existing)
        when {
            !addItems(listOf(item)) -> DownloadEnqueueResult.AlreadyAdded
            replace -> DownloadEnqueueResult.Replaced
            else -> DownloadEnqueueResult.Started
        }
    }

    internal fun enqueueBatch(meta: com.nuvio.app.features.details.MetaDetails,
        episodes: List<com.nuvio.app.features.details.MetaVideo>, selection: DownloadSourceSelection): Int = synchronized(lock) {
        ensureLoaded()
        val items = episodes.map { episode ->
            newItem(meta.type, episode.id, meta.id, meta.type, meta.name, meta.logo, meta.poster, meta.background,
                episode.season, episode.episode, episode.title, episode.thumbnail, "", "Automatic selection", "", selection.providerId)
                .copy(sourceSelection = selection)
        }
        // Queuing a season again retries its failed episodes with the new source preferences.
        val keys = items.map { it.logicalContentKey }.toSet()
        _uiState.value.items.filter { it.logicalContentKey in keys && it.status == DownloadStatus.Failed }.forEach(::removeItem)
        val existingKeys = _uiState.value.items.map { it.logicalContentKey }.toSet()
        val fresh = items.filter { it.logicalContentKey !in existingKeys }.distinctBy { it.logicalContentKey }
        addItems(fresh)
        fresh.size
    }

    private fun newItem(type: String, videoId: String, parentId: String, parentType: String, title: String,
        logo: String?, poster: String?, background: String?, season: Int?, episode: Int?, episodeTitle: String?,
        thumbnail: String?, url: String, streamTitle: String, provider: String, providerId: String?): DownloadItem {
        val now = DownloadsClock.nowEpochMs()
        val id = "${now.toString(36)}_${(++ordinal).toString(36)}"
        return DownloadItem(id = id, contentType = type, parentMetaId = parentId.trim(), parentMetaType = parentType,
            videoId = videoId, title = title, logo = logo, poster = poster, background = background,
            seasonNumber = season, episodeNumber = episode, episodeTitle = episodeTitle, episodeThumbnail = thumbnail,
            streamTitle = streamTitle, providerName = provider, providerAddonId = providerId, sourceUrl = url,
            subtitleRequests = addonSubtitleRequests(type, videoId),
            destinationDirectory = DownloadDesktopFeatures.directory(),
            fileName = buildFileName(title, season, episode, episodeTitle, streamTitle, url, id),
            status = DownloadStatus.Downloading, phase = if (isDesktop) "Queued" else null,
            createdAtEpochMs = now, updatedAtEpochMs = now)
    }

    private fun addItems(items: List<DownloadItem>): Boolean {
        val keys = _uiState.value.items.map { it.logicalContentKey }.toMutableSet()
        val added = items.filter { keys.add(it.logicalContentKey) }
        if (added.isEmpty()) return false
        // Desktop runs the list as a FIFO queue; mobile starts everything and lists newest first.
        publish(if (isDesktop) _uiState.value.items + added else added + _uiState.value.items)
        persist()
        pumpQueue()
        return true
    }

    fun pauseDownload(downloadId: String) = synchronized(lock) {
        ensureLoaded()
        if (_uiState.value.items.none { it.id == downloadId && it.status == DownloadStatus.Downloading }) return
        tokens.remove(downloadId)
        activeHandles.remove(downloadId)?.cancel()
        mutateItem(downloadId) { it.copy(status = DownloadStatus.Paused, phase = null, bytesPerSecond = 0) }
        pumpQueue()
    }
    fun pauseActiveDownloads() = synchronized(lock) {
        ensureLoaded()
        val ids = _uiState.value.items.filter { it.status == DownloadStatus.Downloading }.map { it.id }
        // Mark the whole queue first so pausing cannot start the next task.
        publish(_uiState.value.items.map { if (it.id in ids) it.copy(status = DownloadStatus.Paused, phase = null, bytesPerSecond = 0) else it })
        ids.forEach { tokens.remove(it); activeHandles.remove(it)?.cancel() }
        persist()
    }
    fun resumeAll() = synchronized(lock) {
        ensureLoaded()
        publish(_uiState.value.items.map { if (it.status == DownloadStatus.Paused) it.copy(status = DownloadStatus.Downloading,
            phase = if (isDesktop) "Queued" else null, errorMessage = null) else it })
        persist(); pumpQueue()
    }
    fun resumeDownload(downloadId: String) = synchronized(lock) {
        ensureLoaded()
        mutateItem(downloadId) { if (it.status == DownloadStatus.Paused || it.status == DownloadStatus.Failed)
            it.copy(status = DownloadStatus.Downloading, phase = if (isDesktop) "Queued" else null,
                errorMessage = null, localFileUri = null) else it }
        pumpQueue()
    }
    fun retryDownload(downloadId: String) = resumeDownload(downloadId)
    internal fun reattachBackgroundDownload(downloadId: String) = synchronized(lock) {
        if (!hasLoaded) return
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        tokens.remove(downloadId); activeHandles.remove(downloadId)?.cancel()
        mutateItem(downloadId) { DownloadsPlatformDownloader.restoreItem(item) }
        pumpQueue()
    }
    fun cancelDownload(downloadId: String) = synchronized(lock) {
        ensureLoaded()
        _uiState.value.items.firstOrNull { it.id == downloadId }?.let(::removeItem)
    }

    fun cancelDownloads(downloadIds: Set<String>, expectedProfileId: Int) = synchronized(lock) {
        // A confirmation from another profile must never remove the current profile's files.
        if (com.nuvio.app.features.profiles.ProfileRepository.activeProfileId != expectedProfileId ||
            loadedProfile != expectedProfileId) return
        removeItems(_uiState.value.items.filter { it.id in downloadIds })
    }

    private fun removeItem(item: DownloadItem) = removeItems(listOf(item))

    private fun removeItems(items: List<DownloadItem>) {
        if (items.isEmpty()) return
        val ids = items.map { it.id }.toSet()
        // Invalidate the entire batch before cancellation callbacks or the queue can run.
        ids.forEach(tokens::remove)
        val handles = ids.associateWith { activeHandles.remove(it) }
        publish(_uiState.value.items.filterNot { it.id in ids })
        items.forEach { item ->
            val handle = handles[item.id]
            handle?.cancel()
            // Desktop cleanup waits for closed streams and removes only this task's owned files.
            DownloadDesktopFeatures.remove(item, handle == null)
            if (!isDesktop) {
                DownloadsPlatformDownloader.removeFile(DownloadsPlatformDownloader.resolveLocalFileUri(item.localFileUri, item.fileName) ?: item.localFileUri)
                DownloadsPlatformDownloader.removePartialFile(item.fileName)
            }
        }
        persist(); pumpQueue()
    }

    private fun invalidateTransfers() {
        generation++
        tokens.clear()
        activeHandles.values.forEach(DownloadsTaskHandle::detach)
        activeHandles.clear()
    }
    private fun loadFromDisk() {
        invalidateTransfers()
        hasLoaded = true
        loadedProfile = com.nuvio.app.features.profiles.ProfileRepository.activeProfileId
        _hasUnseenCompleted.value = false
        val items = DownloadsCodec.decodeItems(DownloadsStorage.loadPayload().orEmpty()).map { item ->
            val restored = DownloadsPlatformDownloader.restoreItem(item)
            // Retry re-adopts a proven file in place once a desktop drive is reconnected.
            if (isDesktop && restored.status == DownloadStatus.Completed && !restored.hasPlayableLocalFile()) {
                restored.copy(status = DownloadStatus.Failed, errorMessage = "Downloaded file is missing or its drive is unavailable")
            } else restored
        }
        publish(items); persist(); pumpQueue()
    }
    /** Starts queued downloads after the number allowed at once was raised; running ones are left alone. */
    internal fun onParallelLimitChanged() = synchronized(lock) { if (hasLoaded) pumpQueue() }

    private fun pumpQueue() {
        val limit = if (isDesktop) DownloadDesktopFeatures.parallelDownloads() else Int.MAX_VALUE
        _uiState.value.items.filter { it.status == DownloadStatus.Downloading && it.id !in activeHandles }
            .take((limit - activeHandles.size).coerceAtLeast(0)).forEach { startDownload(it) }
    }
    private fun startDownload(item: DownloadItem, attempt: Int = 1) {
        val token = ++ordinal
        val expectedGeneration = generation
        tokens[item.id] = token
        // Reserve the slot before start(), including platforms that synchronously call back.
        activeHandles[item.id] = object : DownloadsTaskHandle { override fun cancel() {} }
        fun current(): Boolean = loadedProfile == com.nuvio.app.features.profiles.ProfileRepository.activeProfileId &&
            generation == expectedGeneration && tokens[item.id] == token &&
            _uiState.value.items.any { it.id == item.id && it.status == DownloadStatus.Downloading }
        fun finish(transform: (DownloadItem) -> DownloadItem) = synchronized(lock) {
            if (!current()) return@synchronized
            tokens.remove(item.id); activeHandles.remove(item.id)
            mutateItem(item.id, transform = transform); pumpQueue()
        }
        val rate = DownloadRateMeter()
        var sourceResolved = item.sourceSelection == null
        val handle = DownloadsPlatformDownloader.start(DownloadPlatformRequest(item, onResolved = { resolved -> synchronized(lock) {
            sourceResolved = true
            if (current()) mutateItem(item.id) { original -> original.copy(
                streamTitle = resolved.streamTitle, streamSubtitle = resolved.streamSubtitle,
                providerName = resolved.providerName, providerAddonId = resolved.providerAddonId,
                sourceUrl = resolved.sourceUrl, sourceHeaders = resolved.sourceHeaders,
                sourceSubtitles = resolved.sourceSubtitles, torrentHash = resolved.torrentHash,
                torrentFileIdx = resolved.torrentFileIdx, torrentFilename = resolved.torrentFilename,
                torrentTrackers = resolved.torrentTrackers, phase = "Connecting") }
        } }),
            onProgress = { bytes, total -> synchronized(lock) {
                if (current()) {
                    val now = DownloadsClock.nowEpochMs()
                    val speed = rate.sample(now, bytes)
                    // Progress is transient; the durable .part length is the resume offset.
                    val phase = when {
                        !isDesktop -> null
                        !sourceResolved -> "Finding source"
                        bytes == 0L -> "Connecting"
                        else -> "Downloading"
                    }
                    mutateItem(item.id, persist = false) { it.copy(downloadedBytes = bytes, totalBytes = total,
                        phase = phase, bytesPerSecond = speed, updatedAtEpochMs = now) }
                }
            } },
            onSuccess = { uri, total -> finish { it.copy(status = DownloadStatus.Completed, localFileUri = uri,
                downloadedBytes = total ?: it.downloadedBytes, totalBytes = total, phase = null, bytesPerSecond = 0) } },
            onFailure = { message -> synchronized(lock) {
                // The desktop transfer retries interrupted reads itself; mobile retries the whole task.
                if (!isDesktop && attempt < MaxMobileDownloadAttempts && current()) {
                    tokens.remove(item.id); activeHandles.remove(item.id)
                    _uiState.value.items.firstOrNull { it.id == item.id }?.let { startDownload(it, attempt + 1) }
                    return@synchronized
                }
                finish { it.copy(status = DownloadStatus.Failed, phase = null, bytesPerSecond = 0,
                    errorMessage = message.ifBlank { runBlocking { getString(Res.string.download_failed) } }) }
            } },
            onPaused = { finish { it.copy(status = DownloadStatus.Paused, phase = null, bytesPerSecond = 0) } })
        if (current()) activeHandles[item.id] = handle else handle.cancel()
    }
    private fun mutateItem(id: String, persist: Boolean = true, transform: (DownloadItem) -> DownloadItem) {
        if (_uiState.value.items.none { it.id == id }) return
        publish(_uiState.value.items.map { if (it.id == id) transform(it) else it })
        if (persist) persist()
    }
    private fun publish(items: List<DownloadItem>) {
        val old = _uiState.value.items.associate { it.id to it.status }
        if (items.any { it.status == DownloadStatus.Completed && old[it.id] != DownloadStatus.Completed && old[it.id] != null })
            _hasUnseenCompleted.value = true
        _uiState.value = DownloadsUiState(items, profileId = loadedProfile)
        notifyLiveStatusPlatform()
    }
    private fun notifyLiveStatusPlatform() { runCatching { DownloadsLiveStatusPlatform.onItemsChanged(_uiState.value.items) } }
    private fun persist() {
        if (loadedProfile == com.nuvio.app.features.profiles.ProfileRepository.activeProfileId)
            DownloadsStorage.savePayload(DownloadsCodec.encodeItems(_uiState.value.items))
    }
    private fun DownloadItem.hasPlayableLocalFile(): Boolean = status == DownloadStatus.Completed &&
        DownloadsPlatformDownloader.resolveLocalFileUri(localFileUri, fileName) != null
}

@Serializable
private data class StoredDownloadsPayload(
    val items: List<DownloadItem> = emptyList(),
)

private object DownloadsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decodeItems(payload: String): List<DownloadItem> =
        runCatching {
            json.decodeFromString<StoredDownloadsPayload>(payload).items
        }.getOrDefault(emptyList())

    fun encodeItems(items: Collection<DownloadItem>): String =
        json.encodeToString(
            StoredDownloadsPayload(
                items = items.toList(),
            ),
        )
}

private fun sanitizeRequestHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (
                normalizedKey.isBlank() ||
                normalizedValue.isBlank() ||
                normalizedKey.equals("Accept-Encoding", ignoreCase = true) ||
                normalizedKey.equals("Range", ignoreCase = true)
            ) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun sanitizeResponseHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun buildLogicalKey(
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): String = if (seasonNumber != null && episodeNumber != null) {
    "${parentMetaId.trim()}|$seasonNumber|$episodeNumber"
} else {
    "${parentMetaId.trim()}|movie"
}

private fun buildFileName(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    fallbackTitle: String,
    sourceUrl: String,
    downloadId: String,
): String {
    val baseTitle = if (seasonNumber != null && episodeNumber != null) {
        buildString {
            append(title)
            append(" S")
            append(seasonNumber.toString().padStart(2, '0'))
            append('E')
            append(episodeNumber.toString().padStart(2, '0'))
            if (!episodeTitle.isNullOrBlank()) {
                append(' ')
                append(episodeTitle)
            }
        }
    } else {
        title.ifBlank { fallbackTitle }
    }

    val extension = sourceUrl.fileExtensionFromUrl()
    return buildString {
        append(baseTitle.sanitizeFileName().ifBlank { "download" }.take(92))
        append('_')
        append(downloadId)
        append('.')
        append(extension)
    }
}

private fun String.sanitizeFileName(): String =
    trim().replace(Regex("[^A-Za-z0-9._ -]"), "_")

private fun String.fileExtensionFromUrl(): String {
    val withoutQuery = substringBefore('?').substringBefore('#')
    val suffix = withoutQuery.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .trim()

    return if (suffix.length in 2..5 && suffix.all { it.isLetterOrDigit() }) {
        suffix
    } else {
        "mp4"
    }
}

private fun String.isSupportedDownloadUrl(): Boolean {
    val normalized = trim().lowercase()
    if (normalized.startsWith("magnet:")) return false
    if (normalized.endsWith(".m3u8") || normalized.contains(".m3u8?")) return false
    if (normalized.endsWith(".mpd") || normalized.contains(".mpd?")) return false
    if (normalized.endsWith(".torrent") || normalized.contains(".torrent?")) return false
    return normalized.startsWith("http://") || normalized.startsWith("https://")
}

package com.nuvio.app.features.airing

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.userId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.builtins.ListSerializer
import com.nuvio.app.features.home.HomeCatalogSettingsSyncService
import java.time.Instant
import java.time.ZoneId

internal data class AiringState(
    val settings: AiringSettings,
    val snapshot: AiringSnapshot = AiringSnapshot(),
    val now: Instant = Instant.now(),
    val loading: Boolean = false,
    val error: String? = null,
)

internal class AiringRepository(
    initialSettings: AiringSettings,
    initialSnapshot: AiringSnapshot = AiringSnapshot(),
    private val fetch: suspend (AiringSettings, Instant) -> List<AiringEvent>,
    private val saveSnapshot: suspend (AiringSnapshot) -> Unit = {},
    private val saveSettings: suspend (AiringSettings) -> Unit = {},
    private val now: () -> Instant = Instant::now,
    private val fetchUpdates: (suspend (AiringSettings, Instant, suspend (List<AiringEvent>) -> Unit) -> List<AiringEvent>)? = null,
    private val loadSaved: (suspend () -> Pair<AiringSettings, AiringSnapshot>)? = null,
    private val onHiddenChanged: () -> Unit = {},
) {
    private val mutex = Mutex()
    private val refreshMutex = Mutex()
    private val restoreMutex = Mutex()
    private var restored = loadSaved == null
    @Volatile private var generation = 0L
    private var fetchJob: Job? = null
    private val _state = MutableStateFlow(AiringState(initialSettings, validSnapshot(initialSnapshot, initialSettings), now()))
    val state = _state.asStateFlow()
    private var attemptedAt: Long? = null
    private var retryAt = 0L

    fun cancelActive() {
        generation++
        fetchJob?.cancel()
    }

    private fun validSnapshot(snapshot: AiringSnapshot, settings: AiringSettings): AiringSnapshot =
        snapshot.takeIf { it.airType == settings.airType && it.hideNsfw == settings.hideNsfw &&
            it.contentPolicyVersion == AIRING_CONTENT_POLICY_VERSION }
            ?.let { it.copy(events = filterAiringContent(it.events, settings)) }
            ?: AiringSnapshot(airType = settings.airType, hideNsfw = settings.hideNsfw)

    private suspend fun restore() = restoreMutex.withLock {
        if (restored) return@withLock
        _state.update { it.copy(loading = true) }
        val (settings, snapshot) = withContext(Dispatchers.IO) { loadSaved!!.invoke() }
        mutex.withLock { _state.value = AiringState(settings, validSnapshot(snapshot, settings), now()) }
        restored = true
    }

    suspend fun updateSettings(settings: AiringSettings) {
        ZoneId.of(settings.zone)
        require(settings.historyDays in 1..MAX_AIRING_HISTORY_DAYS && settings.airType in listOf("broadcast", "sub", "dub"))
        restore()
        val sourceChanged = mutex.withLock {
            val previous = _state.value
            val settings = settings.copy(hidden = previous.settings.hidden, hiddenSyncPending = previous.settings.hiddenSyncPending)
            saveSettings(settings)
            val changed = previous.settings.airType != settings.airType || previous.settings.hideNsfw != settings.hideNsfw
            if (changed) {
                generation++
                fetchJob?.cancel()
                attemptedAt = null
            }
            _state.value = previous.copy(settings = settings, error = null, loading = if (changed) false else previous.loading,
                snapshot = previous.snapshot.takeIf {
                    it.airType == settings.airType && it.hideNsfw == settings.hideNsfw
                } ?: AiringSnapshot(airType = settings.airType, hideNsfw = settings.hideNsfw))
            if (previous.settings.hideNsfw != settings.hideNsfw) {
                com.nuvio.app.features.details.MetaDetailsRepository.invalidateNativeAiring()
            }
            changed
        }
        if (sourceChanged) refreshInternal(force = true, waitForPrevious = true)
        else if (state.value.snapshot.savedAt == 0L) refresh()
    }

    /** Hides a title everywhere airing is shown; no refetch is needed. */
    suspend fun hide(event: AiringEvent) = updateHidden { hidden ->
        hidden.filterNot { it.mediaId == event.mediaId } +
            HiddenAiringTitle(event.mediaId, event.name, event.poster, now().toEpochMilli())
    }

    suspend fun showAgain(mediaId: Int) = updateHidden { hidden -> hidden.filterNot { it.mediaId == mediaId } }

    suspend fun showAllAgain() = updateHidden { emptyList() }

    private fun hiddenJson(settings: AiringSettings): JsonArray =
        Json.encodeToJsonElement(ListSerializer(HiddenAiringTitle.serializer()), settings.hidden).jsonArray

    suspend fun exportHiddenTitles(): HiddenAiringSyncState {
        restore()
        val settings = state.value.settings
        return HiddenAiringSyncState(hiddenJson(settings), settings.hiddenSyncPending)
    }

    suspend fun applyHiddenTitles(titles: JsonArray, expected: JsonArray? = null) {
        val hidden = Json { ignoreUnknownKeys = true }.decodeFromJsonElement(
            ListSerializer(HiddenAiringTitle.serializer()), titles,
        ).filter { it.mediaId > 0 }.distinctBy { it.mediaId }
        restore()
        mutex.withLock {
            val current = _state.value.settings
            // A hide/restore performed while the pull was in flight takes precedence.
            if (current.hiddenSyncPending || expected != null && hiddenJson(current) != expected) return@withLock
            val settings = current.copy(hidden = hidden)
            saveSettings(settings)
            _state.update { it.copy(settings = settings) }
        }
    }

    suspend fun acknowledgeHiddenTitles(titles: JsonArray) {
        restore()
        mutex.withLock {
            val current = _state.value.settings
            if (!current.hiddenSyncPending || hiddenJson(current) != titles) return@withLock
            val settings = current.copy(hiddenSyncPending = false)
            saveSettings(settings)
            _state.update { it.copy(settings = settings) }
        }
    }

    private suspend fun updateHidden(transform: (List<HiddenAiringTitle>) -> List<HiddenAiringTitle>) {
        restore()
        mutex.withLock {
            val current = _state.value.settings
            val hidden = transform(current.hidden)
            if (hidden == current.hidden) return@withLock
            val settings = current.copy(hidden = hidden, hiddenSyncPending = true)
            saveSettings(settings)
            _state.update { it.copy(settings = settings) }
            onHiddenChanged()
        }
    }

    suspend fun refresh(force: Boolean = false) = refreshInternal(force, waitForPrevious = false)

    private suspend fun refreshInternal(force: Boolean, waitForPrevious: Boolean) {
        if (waitForPrevious) refreshMutex.lock() else if (!refreshMutex.tryLock()) return
        try {
            restore()
            val instant = now()
            val (old, version) = mutex.withLock {
                _state.update { it.copy(now = instant) }
                val current = _state.value
                val age = instant.toEpochMilli() - current.snapshot.savedAt
                if (!force && current.snapshot.complete && current.snapshot.savedAt > 0 && age in 0 until 5 * 60_000L) return
                if (instant.toEpochMilli() < retryAt) return
                if (attemptedAt?.let { instant.toEpochMilli() - it in 0 until 60_000L } == true) return
                attemptedAt = instant.toEpochMilli()
                _state.update { it.copy(loading = true, error = null) }
                current to generation
            }
            var savedPartial = old.snapshot.events.isNotEmpty()
            suspend fun publish(fresh: List<AiringEvent>, complete: Boolean) = mutex.withLock {
                if (version != generation) return@withLock
                val current = _state.value
                val retained = old.snapshot.events.filter { !complete || it.airingAt <= instant.epochSecond }
                val eligible = (retained + fresh).filter { it.airingAt >= instant.minusSeconds(8 * 86400).epochSecond }
                val merged = filterAiringContent(eligible, current.settings)
                    .associateBy { "${it.mediaId}:${it.episode}:${it.airType}" }.values.toList()
                val snapshot = AiringSnapshot(merged, instant.toEpochMilli(), old.settings.airType,
                    old.settings.hideNsfw, AIRING_CONTENT_POLICY_VERSION, complete)
                _state.value = current.copy(snapshot = snapshot, now = now(), loading = !complete, error = null)
                // Persist the first usable batch for offline restarts, then the completed schedule.
                if (complete || !savedPartial && merged.isNotEmpty()) {
                    try { saveSnapshot(snapshot) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    savedPartial = true
                }
            }
            try {
                coroutineScope {
                    val job = currentCoroutineContext().job
                    val active = mutex.withLock {
                        if (version != generation) false else { fetchJob = job; true }
                    }
                    if (!active) return@coroutineScope
                    val fresh = withContext(Dispatchers.Default) {
                        fetchUpdates?.invoke(old.settings, instant) { publish(it, complete = false) }
                            ?: fetch(old.settings, instant)
                    }
                    publish(fresh, complete = true)
                }
            } catch (e: CancellationException) {
                if (version == generation) {
                    _state.update { it.copy(loading = false) }
                    throw e
                }
            } catch (e: Exception) {
                mutex.withLock {
                    if (version == generation) {
                        if (e is AiringRateLimitException) retryAt = maxOf(retryAt, e.retryAt)
                        _state.update { it.copy(now = now(), loading = false, error = e.message ?: "Schedule source is unavailable") }
                    }
                }
            } finally {
                mutex.withLock { if (version == generation) fetchJob = null }
            }
        } finally { refreshMutex.unlock() }
    }

}

internal object NativeAiring {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val store get() = DesktopStorage.store("native_airing")
    val client by lazy { AiringClient() }
    private var activeScope: String? = null
    private var cachedRepository: AiringRepository? = null
    private fun currentScope() = "${AuthRepository.state.value.userId.orEmpty()}:${ProfileScopedKey.of("native_airing")}"
    val repository: AiringRepository get() = synchronized(this) {
        val scope = currentScope()
        if (activeScope != scope || cachedRepository == null) {
            cachedRepository?.cancelActive()
            cachedRepository = createRepository(scope)
            activeScope = scope
        }
        checkNotNull(cachedRepository)
    }
    fun reset() = synchronized(this) {
        cachedRepository?.cancelActive()
        cachedRepository = null
        activeScope = null
    }
    private fun createRepository(scope: String): AiringRepository {
        return AiringRepository(AiringSettings(), fetch = { settings, instant -> client.schedule(settings, instant) },
            fetchUpdates = { settings, instant, onBatch -> client.scheduleWithUpdates(settings, instant, onBatch) },
            loadSaved = { withContext(Dispatchers.IO) {
                val settings = runCatching { decodeSavedAiringSettings(loadScopedSettings(scope)) }
                    .getOrNull()?.takeIf { runCatching { ZoneId.of(it.zone) }.isSuccess && it.historyDays in 1..7 && it.airType in listOf("broadcast", "sub", "dub") }
                    ?.let { it.copy(historyDays = it.historyDays.coerceAtMost(MAX_AIRING_HISTORY_DAYS)) } ?: AiringSettings()
                val snapshot = runCatching { json.decodeFromString<AiringSnapshot>(store.getString("snapshot").orEmpty()) }
                    .getOrDefault(AiringSnapshot())
                settings to snapshot
            } },
            saveSnapshot = { withContext(Dispatchers.IO) { store.putString("snapshot", json.encodeToString(it)) } },
            saveSettings = { withContext(Dispatchers.IO) {
                store.putString("$scope:settings", json.encodeToString(it))
            } },
            onHiddenChanged = { if (currentScope() == scope) HomeCatalogSettingsSyncService.triggerPush() },
        )
    }

    internal fun loadScopedSettings(scope: String, storage: DesktopStorage.Store = store): String {
        storage.getString("$scope:settings")?.let { return it }
        // The old desktop list belonged to the installation. Claim it once for
        // the first profile opened after upgrading, never for every new profile.
        val legacy = runCatching { decodeSavedAiringSettings(storage.getString("settings").orEmpty()) }
            .getOrDefault(AiringSettings())
        val settings = if (storage.getString("hidden_migrated") == null) {
            legacy
        } else {
            legacy.copy(hidden = emptyList(), hiddenSyncPending = false)
        }
        val payload = json.encodeToString(settings)
        storage.putString("$scope:settings", payload)
        storage.putString("hidden_migrated", "true")
        return payload
    }
}

internal actual suspend fun exportBuiltInAiringHiddenTitles(): HiddenAiringSyncState? = NativeAiring.repository.exportHiddenTitles()
internal actual suspend fun applyBuiltInAiringHiddenTitles(titles: JsonArray, expected: JsonArray?) = NativeAiring.repository.applyHiddenTitles(titles, expected)
internal actual suspend fun acknowledgeBuiltInAiringHiddenTitles(titles: JsonArray) = NativeAiring.repository.acknowledgeHiddenTitles(titles)

internal actual suspend fun fetchBuiltInAiringDetails(id: String): com.nuvio.app.features.details.MetaDetails? {
    val number = id.removePrefix(NATIVE_AIRING_PREFIX).toIntOrNull()?.takeIf { it > 0 } ?: return null
    val state = NativeAiring.repository.state.value
    return NativeAiring.client.details(number, state.snapshot.events, state.settings)
}

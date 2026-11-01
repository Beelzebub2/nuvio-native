package com.nuvio.app.features.airing

import android.content.Context
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.userId
import com.nuvio.app.core.storage.PlatformLocalAccountDataCleaner
import com.nuvio.app.core.storage.ProfileScopedKey
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

    /** Prevent late network responses from an old profile repopulating its state. */
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

    private suspend fun updateHidden(transform: (List<HiddenAiringTitle>) -> List<HiddenAiringTitle>) {
        restore()
        mutex.withLock {
            val settings = _state.value.settings.let { it.copy(hidden = transform(it.hidden)) }
            saveSettings(settings)
            _state.update { it.copy(settings = settings) }
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
    private val json = Json { ignoreUnknownKeys = true }
    private val store get() = PlatformLocalAccountDataCleaner.appContext
        ?.getSharedPreferences("nuvio_native_airing", Context.MODE_PRIVATE)
    val client by lazy { AiringClient() }
    private var activeScope: String? = null
    private var cachedRepository: AiringRepository? = null
    val repository: AiringRepository get() = synchronized(this) {
        val scope = "${AuthRepository.state.value.userId.orEmpty()}:${ProfileScopedKey.of("native_airing")}"
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
        fun stored(key: String) = store?.getString("$scope:$key", null)
        fun save(key: String, payload: String) {
            store?.edit()?.putString("$scope:$key", payload)?.apply()
        }
        return AiringRepository(AiringSettings(), fetch = { settings, instant -> client.schedule(settings, instant) },
            fetchUpdates = { settings, instant, onBatch -> client.scheduleWithUpdates(settings, instant, onBatch) },
            loadSaved = { withContext(Dispatchers.IO) {
                val settings = runCatching { json.decodeFromString<AiringSettings>(stored("settings").orEmpty()) }
                    .getOrNull()?.takeIf { runCatching { ZoneId.of(it.zone) }.isSuccess && it.historyDays in 1..7 && it.airType in listOf("broadcast", "sub", "dub") }
                    ?.let { it.copy(historyDays = it.historyDays.coerceAtMost(MAX_AIRING_HISTORY_DAYS)) } ?: AiringSettings()
                val snapshot = runCatching { json.decodeFromString<AiringSnapshot>(stored("snapshot").orEmpty()) }
                    .getOrDefault(AiringSnapshot())
                settings to snapshot
            } },
            saveSnapshot = { withContext(Dispatchers.IO) { save("snapshot", json.encodeToString(it)) } },
            saveSettings = { withContext(Dispatchers.IO) { save("settings", json.encodeToString(it)) } },
        )
    }

}

internal actual suspend fun fetchBuiltInAiringDetails(id: String): com.nuvio.app.features.details.MetaDetails? {
    val number = id.removePrefix(NATIVE_AIRING_PREFIX).toIntOrNull()?.takeIf { it > 0 } ?: return null
    val state = NativeAiring.repository.state.value
    return NativeAiring.client.details(number, state.snapshot.events, state.settings)
}

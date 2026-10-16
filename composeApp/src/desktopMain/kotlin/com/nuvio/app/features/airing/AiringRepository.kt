package com.nuvio.app.features.airing

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.CancellationException
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
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(AiringState(initialSettings, initialSnapshot.takeIf {
        it.airType == initialSettings.airType && it.hideNsfw == initialSettings.hideNsfw
    } ?: AiringSnapshot(airType = initialSettings.airType, hideNsfw = initialSettings.hideNsfw), now()))
    val state = _state.asStateFlow()
    private var attemptedAt = 0L

    suspend fun updateSettings(settings: AiringSettings) {
        ZoneId.of(settings.zone)
        require(settings.historyDays in 1..7 && settings.airType in listOf("broadcast", "sub", "dub"))
        mutex.withLock {
            val previous = _state.value
            saveSettings(settings)
            _state.value = previous.copy(settings = settings, error = null,
                snapshot = previous.snapshot.takeIf {
                    it.airType == settings.airType && it.hideNsfw == settings.hideNsfw
                } ?: AiringSnapshot(airType = settings.airType, hideNsfw = settings.hideNsfw))
            if (previous.settings.hideNsfw != settings.hideNsfw) {
                com.nuvio.app.features.details.MetaDetailsRepository.invalidateNativeAiring()
            }
            attemptedAt = 0
        }
        refresh(force = true)
    }

    suspend fun refresh(force: Boolean = false) = mutex.withLock {
        val instant = now()
        _state.update { it.copy(now = instant) }
        val old = _state.value
        val age = instant.toEpochMilli() - old.snapshot.savedAt
        if (!force && old.snapshot.savedAt > 0 && age in 0 until 5 * 60_000L) return@withLock
        if (!force && instant.toEpochMilli() - attemptedAt in 0 until 60_000L) return@withLock
        attemptedAt = instant.toEpochMilli()
        _state.update { it.copy(loading = true) }
        try {
            val fresh = fetch(old.settings, instant)
            val cutoff = instant.minusSeconds(8 * 86400).epochSecond
            val retainedPast = old.snapshot.events.filter { it.airingAt <= instant.epochSecond }
            val eligible = (retainedPast + fresh).filter { it.airingAt >= cutoff }
            val merged = filterAiringContent(eligible, old.settings)
                .associateBy { "${it.mediaId}:${it.episode}:${it.airType}" }.values.toList()
            val snapshot = AiringSnapshot(merged, instant.toEpochMilli(),
                old.settings.airType, old.settings.hideNsfw)
            // Persistence failure must not throw away a successful live response.
            try { saveSnapshot(snapshot) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            _state.value = old.copy(snapshot = snapshot, now = now(), error = null)
        } catch (e: CancellationException) {
            _state.update { it.copy(loading = false) }
            throw e
        } catch (e: Exception) {
            _state.value = old.copy(now = now(), error = e.message ?: "Schedule source is unavailable")
        }
    }
}

internal object NativeAiring {
    private val json = Json { ignoreUnknownKeys = true }
    private val store by lazy { DesktopStorage.store("native_airing") }
    val client = AiringClient()
    val repository by lazy {
        val settings = runCatching { json.decodeFromString<AiringSettings>(store.getString("settings").orEmpty()) }
            .getOrNull()?.takeIf { runCatching { ZoneId.of(it.zone) }.isSuccess && it.historyDays in 1..7 && it.airType in listOf("broadcast", "sub", "dub") }
            ?: AiringSettings()
        val snapshot = runCatching { json.decodeFromString<AiringSnapshot>(store.getString("snapshot").orEmpty()) }
            .getOrDefault(AiringSnapshot())
        AiringRepository(settings, snapshot, client::schedule,
            saveSnapshot = { withContext(Dispatchers.IO) { store.putString("snapshot", json.encodeToString(it)) } },
            saveSettings = { withContext(Dispatchers.IO) { store.putString("settings", json.encodeToString(it)) } },
        )
    }
}

internal actual suspend fun fetchBuiltInAiringDetails(id: String): com.nuvio.app.features.details.MetaDetails? {
    val number = id.removePrefix(NATIVE_AIRING_PREFIX).toIntOrNull()?.takeIf { it > 0 } ?: return null
    val state = NativeAiring.repository.state.value
    return NativeAiring.client.details(number, state.snapshot.events, state.settings)
}

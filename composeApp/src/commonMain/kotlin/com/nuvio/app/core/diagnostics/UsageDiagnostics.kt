package com.nuvio.app.core.diagnostics

import com.nuvio.app.core.build.AppVersionPolicy
import com.nuvio.app.core.storage.AnonymousWorkspaceStorage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Fixed, non-identifying event names. Never attach content, account, URL or search data. */
internal enum class UsageEvent(val wireName: String) {
    AppOpen("app_open"),
    Home("screen_home"),
    Search("screen_search"),
    Library("screen_library"),
    Settings("screen_settings"),
    PlaybackOpened("playback_opened"),
    PlaybackReady("playback_ready"),
    PlaybackFailed("playback_failed"),
    DownloadQueued("download_queued"),
    DownloadCompleted("download_completed"),
    DownloadFailed("download_failed"),
}

internal enum class DiagnosticsConsent { Undecided, Disabled, Enabled }

internal fun consentFromStoredPreference(saved: String?): DiagnosticsConsent = when (saved) {
    "yes" -> DiagnosticsConsent.Enabled
    "no" -> DiagnosticsConsent.Disabled
    else -> DiagnosticsConsent.Undecided
}

internal fun isValidUsageVersion(version: String): Boolean =
    Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}(?:[-+][a-zA-Z0-9.-]{1,24})?""").matches(version)

internal expect val diagnosticsPlatform: String
internal expect fun createDiagnosticsInstallationId(): String

/**
 * Device-scoped consent independent of accounts and profiles. No network request is possible
 * until the user explicitly enables analytics. Disabling cancels outstanding sends and discards
 * the local pseudonymous installation ID.
 */
internal object UsageDiagnostics {
    private const val ConsentKey = "usage_diagnostics_consent_v1"
    private const val InstallationKey = "usage_diagnostics_installation_v1"
    private const val Endpoint = "https://starlit-api.rrmtools.uk/gateway/telemetry/events"

    private val _consent = MutableStateFlow(DiagnosticsConsent.Undecided)
    val consent: StateFlow<DiagnosticsConsent> = _consent.asStateFlow()
    private var loaded = false
    private var sessionReported = false
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http by lazy {
        HttpClient {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 3_000
                requestTimeoutMillis = 5_000
            }
        }
    }

    fun ensureLoaded() {
        if (loaded) return
        // Fail closed: absent storage or errors never grant consent.
        val saved = runCatching { AnonymousWorkspaceStorage.loadValue(ConsentKey) }.getOrNull()
        _consent.value = consentFromStoredPreference(saved)
        loaded = true
    }

    fun setEnabled(enabled: Boolean) {
        ensureLoaded()
        val next = if (enabled) DiagnosticsConsent.Enabled else DiagnosticsConsent.Disabled
        if (!enabled) {
            // Stop in-flight work before persisting the revoked state.
            _consent.value = DiagnosticsConsent.Disabled
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            sessionReported = false
        }
        try {
            AnonymousWorkspaceStorage.saveValue(ConsentKey, if (enabled) "yes" else "no")
            if (!enabled) AnonymousWorkspaceStorage.saveValue(InstallationKey, null)
        } catch (_: Exception) {
            // Storage failure must never opt a user in. A declined prompt should still close.
            _consent.value = DiagnosticsConsent.Disabled
            return
        }
        _consent.value = next
        if (enabled) onAppOpen()
    }

    fun onAppOpen() {
        ensureLoaded()
        if (_consent.value != DiagnosticsConsent.Enabled || sessionReported) return
        sessionReported = true
        track(UsageEvent.AppOpen)
    }

    fun track(event: UsageEvent) {
        ensureLoaded()
        if (_consent.value != DiagnosticsConsent.Enabled) return
        val version = AppVersionPolicy.displayVersionName
        if (!isValidUsageVersion(version)) return
        val id = try {
            AnonymousWorkspaceStorage.loadValue(InstallationKey) ?: createDiagnosticsInstallationId().also {
                AnonymousWorkspaceStorage.saveValue(InstallationKey, it)
            }
        } catch (_: Exception) {
            return
        }
        val payload = buildJsonObject {
            put("installationId", id)
            put("platform", diagnosticsPlatform)
            put("version", version)
            put("event", event.wireName)
        }.toString()
        scope.launch {
            if (_consent.value != DiagnosticsConsent.Enabled) return@launch
            try {
                http.post(Endpoint) {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Usage events are best-effort, never block playback, login or navigation.
            }
        }
    }
}

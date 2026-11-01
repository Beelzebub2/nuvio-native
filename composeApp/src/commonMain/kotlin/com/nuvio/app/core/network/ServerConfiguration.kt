package com.nuvio.app.core.network

import com.nuvio.app.core.build.AppFeaturePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServerCapabilities(
    val emailPasswordAuth: Boolean,
    val tvLogin: Boolean,
)

data class ServerConfiguration(
    val backendUrl: String,
    val publishableKey: String,
    val capabilities: ServerCapabilities,
    val isCustom: Boolean,
    val discoveryUrl: String? = null,
    val fallbackBackendUrl: String? = null,
) {
    val isSecure: Boolean
        get() = backendUrl.startsWith("https://", ignoreCase = true)

    val isPublicHost: Boolean
        get() = isPublicServerHost(backendUrl)

    // The built-in Starlit server implements sync, not Nuvio's membership,
    // remote cosmetic catalogs, profile PINs or account-management functions.
    val supportsNuvioAccountFeatures: Boolean
        get() = !isStarlitBackend(backendUrl)

    val isSelfHosted: Boolean
        get() = isCustom || !supportsNuvioAccountFeatures
}

object ServerConfigurationRepository {
    private val _active = MutableStateFlow(loadActiveConfiguration())
    val active: StateFlow<ServerConfiguration> = _active.asStateFlow()

    fun saveCustom(configuration: ServerConfiguration): Boolean {
        if (!AppFeaturePolicy.customServerConnectionsEnabled) return false
        if (!isAllowedAccountServerUrl(configuration.backendUrl)) return false
        if (!ServerConfigurationStorage.saveCustom(configuration)) return false
        _active.value = configuration
        return true
    }

    fun useOfficial(): Boolean {
        if (!ServerConfigurationStorage.useOfficial()) return false
        _active.value = officialConfiguration()
        return true
    }

    private fun loadActiveConfiguration(): ServerConfiguration {
        if (!AppFeaturePolicy.customServerConnectionsEnabled) return officialConfiguration()
        return ServerConfigurationStorage.loadCustom()?.takeIf { isAllowedAccountServerUrl(it.backendUrl) }
            ?: officialConfiguration()
    }
}

/** Applies to saved configurations as well as freshly discovered servers. */
internal fun isAllowedAccountServerUrl(raw: String): Boolean {
    if (raw.isBlank() || raw.contains('@') || raw.contains('\\')) return false
    val url = runCatching { io.ktor.http.Url(raw) }.getOrNull() ?: return false
    if (url.host.isBlank() || url.fragment.isNotBlank() || url.parameters.entries().isNotEmpty()) return false
    return url.protocol.name == "https" ||
        (url.protocol.name == "http" && url.host.lowercase() in setOf("127.0.0.1", "localhost", "::1"))
}

internal fun officialConfiguration() = ServerConfiguration(
    backendUrl = SupabaseConfig.URL.trim().trimEnd('/'),
    publishableKey = SupabaseConfig.ANON_KEY.trim(),
    capabilities = ServerCapabilities(
        emailPasswordAuth = true,
        tvLogin = !isStarlitBackend(SupabaseConfig.URL),
    ),
    isCustom = false,
    fallbackBackendUrl = SupabaseConfig.FALLBACK_URL.trim().trimEnd('/').takeIf { it.isNotBlank() },
)

private fun isStarlitBackend(url: String): Boolean =
    url.trim().trimEnd('/').equals(SupabaseConfig.STARLIT_URL, ignoreCase = true)

internal fun isPublicServerHost(url: String): Boolean {
    val host = runCatching { io.ktor.http.Url(url).host.lowercase() }.getOrNull() ?: return true
    if (host == "localhost" || host.endsWith(".local") || host == "::1") return false
    if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.")) return false
    val parts = host.split('.')
    if (parts.size == 4) {
        val first = parts[0].toIntOrNull()
        val second = parts[1].toIntOrNull()
        if (first == 172 && second != null && second in 16..31) return false
        if (first == 169 && second == 254) return false
    }
    return true
}

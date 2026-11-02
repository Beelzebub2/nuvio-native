package com.nuvio.app.core.auth

import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.features.addons.httpRequestRaw
import io.ktor.http.Url
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val STARLIT_GOOGLE_ORIGIN = "https://starlit-api.rrmtools.uk"

internal expect suspend fun launchGoogleOAuth(client: SupabaseClient)

internal fun isGoogleLoginAdvertised(document: String): Boolean = runCatching {
    val payload = Json.parseToJsonElement(document).jsonObject
    payload["backend_url"]?.jsonPrimitive?.content == STARLIT_GOOGLE_ORIGIN &&
        payload["capabilities"]?.jsonObject?.get("google_login") == JsonPrimitive(true)
}.getOrDefault(false)

/** Only authorization codes are accepted; the SDK exchanges them with its saved PKCE verifier. */
internal fun isStarlitOAuthCallback(url: String): Boolean = runCatching {
    val parsed = Url(url)
    parsed.protocol.name == "nuvio" && parsed.host == "auth" &&
        parsed.encodedPath in listOf("", "/") && parsed.fragment.isEmpty() &&
        parsed.parameters["code"]?.isNotBlank() == true
}.getOrDefault(false)

internal object GoogleOAuthAvailability {
    fun isOfficialServer(): Boolean =
        ServerConfigurationRepository.active.value.backendUrl.trimEnd('/') == STARLIT_GOOGLE_ORIGIN

    suspend fun isEnabled(): Boolean {
        if (!isOfficialServer()) return false
        return try {
            val response = withTimeout(10_000L) {
                httpRequestRaw("GET", "$STARLIT_GOOGLE_ORIGIN/.well-known/nuvio",
                    mapOf("Accept" to "application/json"), "", maxResponseBodyBytes = 65_537)
            }
            response.status == 200 && response.body.encodeToByteArray().size <= 65_536 &&
                isGoogleLoginAdvertised(response.body) &&
                isOfficialServer()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }
}

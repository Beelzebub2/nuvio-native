package com.nuvio.app.core.auth

import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.build.AppVersionConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Recovery is queued server-side and handled by GoTrue; the client never processes reset tokens. */
internal const val STARLIT_PASSWORD_RECOVERY_URL = "https://starlit-api.rrmtools.uk/account/recovery"

internal fun isOfficialPasswordRecoveryServer(url: String): Boolean =
    url.trimEnd('/') == "https://starlit-api.rrmtools.uk"

/** Only minimal input validation; the backend remains responsible for account lookup. */
internal fun normalizePasswordRecoveryEmail(email: String): String? {
    val normalized = email.trim().lowercase()
    if (normalized.length !in 3..254 || normalized.any(Char::isWhitespace)) return null
    val separator = normalized.indexOf('@')
    if (separator < 1 || separator != normalized.lastIndexOf('@')) return null
    val domain = normalized.substring(separator + 1)
    if (domain.length < 3 || !domain.contains('.') || domain.startsWith('.') || domain.endsWith('.')) return null
    return normalized
}

enum class PasswordRecoveryRequestResult {
    SENT,
    INVALID_EMAIL,
    UNAVAILABLE,
    FAILED,
}

object PasswordRecoveryRepository {
    private val client by lazy {
        HttpClient {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 7_000
                requestTimeoutMillis = 15_000
            }
        }
    }

    fun isAvailableOnActiveServer(): Boolean =
        isOfficialPasswordRecoveryServer(ServerConfigurationRepository.active.value.backendUrl)

    suspend fun requestPasswordReset(email: String): PasswordRecoveryRequestResult {
        val normalized = normalizePasswordRecoveryEmail(email) ?: return PasswordRecoveryRequestResult.INVALID_EMAIL
        if (!isAvailableOnActiveServer()) return PasswordRecoveryRequestResult.UNAVAILABLE
        return try {
            val config = ServerConfigurationRepository.active.value
            // Starlit's shared Auth client uses PKCE for app sign-in. A reset link
            // opened in a different browser cannot retrieve that app's PKCE verifier.
            // Submit without a code_challenge. The server selects the canonical
            // HTTPS completion page before invoking native GoTrue recovery.
            val response = client.post("https://starlit-api.rrmtools.uk/auth/v1/recover") {
                url { parameters.append("redirect_to", STARLIT_PASSWORD_RECOVERY_URL) }
                header("apikey", config.publishableKey)
                header("User-Agent", "NuvioMobile/${AppVersionConfig.VERSION_NAME.ifBlank { "dev" }}")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("email", normalized) }.toString())
            }
            // The server queues without account lookup and replies generically.
            // Only transport/ingress errors (including IP throttles) reach here.
            // Never surface raw response bodies that could reveal private details.
            if (response.status.value in 200..299) PasswordRecoveryRequestResult.SENT
            else PasswordRecoveryRequestResult.FAILED
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Never return a raw GoTrue error: it could reveal whether the account exists.
            PasswordRecoveryRequestResult.FAILED
        }
    }
}

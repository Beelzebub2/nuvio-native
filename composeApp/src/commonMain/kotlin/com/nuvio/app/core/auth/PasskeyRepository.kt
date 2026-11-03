package com.nuvio.app.core.auth

import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.user.UserSession
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Platform-bound, interactive WebAuthn ceremonies. Private keys never enter app code. */
interface PasskeyCeremony {
    val supportsRegistration: Boolean get() = true
    suspend fun create(optionsJson: String): String
    suspend fun get(optionsJson: String): String
}

/** Native providers (including Bitwarden) can reject Android app verification independently of GoTrue. */
internal fun passkeyProviderErrorMessage(description: String?, registration: Boolean): String {
    val lower = description.orEmpty().lowercase()
    val action = if (registration) "create" else "use"
    return when {
        "cancel" in lower -> "Passkey operation canceled."
        "asset link" in lower || "app not supported" in lower || "not supported for this app" in lower ||
            "app not found" in lower ->
            "Your passkey manager couldn't verify this app. If you're using Bitwarden, update it and retry, " +
                "or select Google Password Manager in Android settings. Starlit cannot bypass provider verification."
        "no provider" in lower || "provider configuration" in lower ->
            "No compatible passkey manager is available. Enable one in Android's password/passkey settings and retry."
        else -> "Couldn't $action a passkey with the selected manager. Update it or try another passkey provider."
    }
}

@androidx.compose.runtime.Composable
expect fun rememberPasskeyCeremony(): PasskeyCeremony?

private const val STARLIT_RP_ORIGIN = "https://starlit-api.rrmtools.uk"

/** Do not offer credentials to arbitrary/custom servers pretending to be the Starlit RP. */
internal fun isTrustedPasskeyOrigin(url: String): Boolean =
    url.trimEnd('/') == STARLIT_RP_ORIGIN

/** Treat the capability as false unless the official backend explicitly enabled it. */
internal fun isPasskeyAdvertised(document: String): Boolean = runCatching {
    val doc = Json.parseToJsonElement(document).jsonObject
    doc["backend_url"]?.jsonPrimitive?.content == STARLIT_RP_ORIGIN &&
        doc["capabilities"]?.jsonObject?.get("passkey_login") == JsonPrimitive(true)
}.getOrDefault(false)

internal data class PasskeyOptions(val challengeId: String, val optionsJson: String)

data class RegisteredPasskey(val id: String, val name: String, val createdAt: String)

/** The server generates/consumes its own one-use challenge. No client-generated challenges. */
internal fun parsePasskeyOptions(body: String): PasskeyOptions {
    val json = Json.parseToJsonElement(body).jsonObject
    val id = json["challenge_id"]?.jsonPrimitive?.content.orEmpty()
    require(id.length in 16..80 && id.all { it.isLetterOrDigit() || it == '-' }) {
        "Passkey challenge was invalid"
    }
    val options = requireNotNull(json["options"] as? JsonObject) { "Passkey options were missing" }
    require(options["challenge"]?.jsonPrimitive?.content?.isNotBlank() == true) {
        "Passkey challenge was missing"
    }
    return PasskeyOptions(id, options.toString())
}

internal fun passkeyVerificationBody(challengeId: String, credentialJson: String): String {
    val credential = Json.parseToJsonElement(credentialJson).jsonObject
    require(credential["id"]?.jsonPrimitive?.content?.isNotBlank() == true) {
        "The device did not return a passkey"
    }
    return buildJsonObject {
        put("challenge_id", challengeId)
        put("credential", credential)
    }.toString()
}

/** Supabase GoTrue's native WebAuthn endpoints; server checks RP, UV, signatures and replay. */
object PasskeyRepository {
    fun canUseOnActiveServer(): Boolean =
        isTrustedPasskeyOrigin(ServerConfigurationRepository.active.value.backendUrl)

    suspend fun isEnabledOnActiveServer(): Boolean {
        if (!canUseOnActiveServer()) return false
        return try {
            val response = http.get("$STARLIT_RP_ORIGIN/.well-known/nuvio")
            response.status.value == 200 && isPasskeyAdvertised(response.bodyAsText())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    private val http by lazy {
        HttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 30_000
            }
        }
    }

    private suspend fun post(path: String, payload: String, token: String? = null): String {
        val server = ServerConfigurationRepository.active.value
        require(isTrustedPasskeyOrigin(server.backendUrl)) { "Passkeys are only available for the official Starlit server" }
        require(server.publishableKey.isNotBlank()) { "Starlit account server is not configured" }
        val response = http.post("$STARLIT_RP_ORIGIN/auth/v1/passkeys/$path") {
            header("apikey", server.publishableKey)
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        // Do not surface arbitrary server error bodies, credentials or challenge data to the UI.
        if (response.status.value !in 200..299) {
            val code = runCatching {
                Json.parseToJsonElement(response.bodyAsText()).jsonObject["error_code"]
                    ?.jsonPrimitive?.content
            }.getOrNull()
            error(when (code) {
                "passkey_disabled" -> "Passkeys are not enabled on this server yet"
                "too_many_passkeys" -> "This account already has the maximum number of passkeys"
                "webauthn_credential_exists" -> "This passkey is already registered"
                "webauthn_challenge_expired", "webauthn_challenge_not_found" -> "Passkey request expired. Try again"
                "email_not_confirmed", "user_banned" -> "This account is not approved for sign-in"
                else -> "Passkey request could not be verified. Try again"
            })
        }
        return response.bodyAsText()
    }

    /** A fresh password sign-in is required before any durable credential mutation. */
    internal suspend fun reauthenticate(password: String) {
        require(password.isNotBlank()) { "Enter your account password to continue" }
        val state = requireNotNull(AuthRepository.state.value as? AuthState.Authenticated)
        require(!state.isAnonymous && !state.email.isNullOrBlank()) { "An approved account is required" }
        val emailAddress = requireNotNull(state.email)
        val auth = SupabaseProvider.client.auth
        // Unlike using an old cached access token alone, this proves current password possession.
        auth.signInWith(Email) {
            email = emailAddress
            this.password = password
        }
        if (auth.currentSessionOrNull()?.user?.id != state.userId) {
            auth.clearSession()
            error("The account changed during password confirmation")
        }
    }

    suspend fun signIn(ceremony: PasskeyCeremony) {
        val serverAtStart = ServerConfigurationRepository.active.value.backendUrl
        require(isTrustedPasskeyOrigin(serverAtStart)) { "Passkeys are not available for this server" }
        val challenge = parsePasskeyOptions(post("authentication/options", "{}"))
        val credential = ceremony.get(challenge.optionsJson)
        check(ServerConfigurationRepository.active.value.backendUrl == serverAtStart) {
            "The account server changed during passkey sign-in"
        }
        val result = Json.parseToJsonElement(post("authentication/verify",
            passkeyVerificationBody(challenge.challengeId, credential))).jsonObject
        val token = requireNotNull(result["access_token"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() })
        val refresh = requireNotNull(result["refresh_token"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() })
        val expiresIn = requireNotNull(result["expires_in"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 })
        check(ServerConfigurationRepository.active.value.backendUrl == serverAtStart) {
            "The account server changed during passkey verification"
        }
        // Reuse the existing client session handler: it safely migrates/isolates guest data.
        val auth = SupabaseProvider.client.auth
        val user = auth.retrieveUser(token)
        AuthRepository.expectRemoteSession()
        try {
            auth.importSession(UserSession(
                accessToken = token,
                refreshToken = refresh,
                expiresIn = expiresIn,
                tokenType = "bearer",
                user = user,
            ))
        } catch (error: Throwable) {
            AuthRepository.cancelRemoteSessionRequest()
            throw error
        }
    }

    suspend fun register(ceremony: PasskeyCeremony, password: String) {
        val serverAtStart = ServerConfigurationRepository.active.value.backendUrl
        require(isTrustedPasskeyOrigin(serverAtStart)) { "Passkeys are not available for this server" }
        val state = AuthRepository.state.value as? AuthState.Authenticated
        require(state != null && !state.isAnonymous) { "Sign in to an approved account first" }
        reauthenticate(password)
        val auth = SupabaseProvider.client.auth
        val session = requireNotNull(auth.currentSessionOrNull()) { "Sign in again before adding a passkey" }
        val token = session.accessToken
        // The server ties this challenge to the authenticated user, not a supplied email/id.
        val challenge = parsePasskeyOptions(post("registration/options", "{}", token))
        val credential = ceremony.create(challenge.optionsJson)
        check(ServerConfigurationRepository.active.value.backendUrl == serverAtStart &&
            auth.currentSessionOrNull()?.user?.id == state.userId &&
            auth.currentSessionOrNull()?.accessToken == token) {
            "The signed-in account changed while creating the passkey"
        }
        post("registration/verify", passkeyVerificationBody(challenge.challengeId, credential), token)
    }

    suspend fun list(): List<RegisteredPasskey> {
        val config = ServerConfigurationRepository.active.value
        require(isTrustedPasskeyOrigin(config.backendUrl)) { "Passkeys are not available on this server" }
        val token = requireNotNull(SupabaseProvider.client.auth.currentSessionOrNull()?.accessToken)
        val response = http.get("$STARLIT_RP_ORIGIN/auth/v1/passkeys") {
            header("apikey", config.publishableKey)
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        check(response.status.value in 200..299) { "Couldn't load passkeys" }
        return Json.parseToJsonElement(response.bodyAsText()).jsonArray.map { item ->
            val entry = item.jsonObject
            RegisteredPasskey(
                id = entry.getValue("id").jsonPrimitive.content,
                name = entry["friendly_name"]?.jsonPrimitive?.content.orEmpty().ifBlank { "Passkey" },
                createdAt = entry["created_at"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }

    suspend fun revoke(id: String, password: String) {
        require(Regex("^[0-9a-fA-F-]{36}$").matches(id)) { "Invalid passkey" }
        val origin = ServerConfigurationRepository.active.value.backendUrl
        require(isTrustedPasskeyOrigin(origin)) { "Passkeys are not available on this server" }
        reauthenticate(password)
        val auth = SupabaseProvider.client.auth
        val config = ServerConfigurationRepository.active.value
        check(config.backendUrl == origin) { "The account server changed" }
        val token = requireNotNull(auth.currentSessionOrNull()?.accessToken)
        val response = http.delete("$STARLIT_RP_ORIGIN/auth/v1/passkeys/$id") {
            header("apikey", config.publishableKey)
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        check(response.status.value == 204) { "Couldn't remove this passkey" }
    }
}

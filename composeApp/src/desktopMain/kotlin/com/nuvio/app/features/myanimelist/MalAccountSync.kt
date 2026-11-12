package com.nuvio.app.features.myanimelist

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.auth.auth
import com.nuvio.app.core.network.ServerConfigurationRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal fun malAccountCredential(tokens: MalTokens): JsonObject = buildJsonObject {
    put("client_id", tokens.clientId)
    put("access_token", tokens.accessToken)
    put("refresh_token", tokens.refreshToken)
    put("expires_at", tokens.expiresAt)
    put("username", tokens.username)
    put("client_secret", tokens.clientSecret)
}

internal fun parseMalAccountCredential(value: JsonObject): MalTokens? {
    fun text(key: String) = value[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    val client = text("client_id")
    val access = text("access_token")
    val refresh = text("refresh_token")
    if (!client.matches(Regex("[A-Za-z0-9_-]{16,128}")) || access.isBlank() || refresh.isBlank() ||
        access.length > 8192 || refresh.length > 8192) return null
    val expires = value["expires_at"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return null
    return MalTokens(client, access, refresh, expires, text("username"), text("client_secret"))
}

internal data class MalBoundSession(val owner: String, val backendUrl: String, val publishableKey: String, val bearer: String)

internal suspend fun boundMalAccountRpc(session: MalBoundSession, function: String, payload: JsonObject): JsonElement {
    require(function in setOf("sync_push_provider_credentials", "sync_pull_provider_credentials"))
    val request = Request.Builder().url(session.backendUrl.trimEnd('/') + "/rest/v1/rpc/" + function)
        .header("Authorization", "Bearer ${session.bearer}")
        .header("apikey", session.publishableKey)
        .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
    return suspendCancellableCoroutine { continuation ->
        val call = malAccountHttp.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) { if(continuation.isActive)continuation.resumeWithException(IOException("Account credential sync unavailable")) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if(!it.isSuccessful)throw IOException("Account credential sync failed")
                        val bytes = it.body?.byteStream()?.use { stream -> stream.readNBytes(1_048_577) } ?: ByteArray(0)
                        check(bytes.size <= 1_048_576) { "Account response too large" }
                        if(bytes.isEmpty()) JsonNull else Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
                    }
                    if(continuation.isActive)continuation.resume(result)
                } catch(_: Exception) { if(continuation.isActive)continuation.resumeWithException(IOException("Account credential sync failed")) }
            }
        })
    }
}
private val malAccountHttp = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

internal object MalAccountSync {
    fun owner(): String? = (AuthRepository.state.value as? AuthState.Authenticated)
        ?.takeUnless { it.isAnonymous }?.userId

    private fun captureSession(expectedOwner: String): MalBoundSession {
        val server = ServerConfigurationRepository.active.value
        val client = SupabaseProvider.client
        val auth = client.auth.currentSessionOrNull() ?: error("Sign in again before syncing MyAnimeList.")
        check(auth.user?.id == expectedOwner && auth.accessToken.isNotBlank() && SupabaseProvider.client === client && ServerConfigurationRepository.active.value.backendUrl == server.backendUrl) { "Account changed" }
        return MalBoundSession(expectedOwner, server.backendUrl, server.publishableKey, auth.accessToken)
    }

    private fun requireScope(expectedOwner: String, profile: Int, isCurrent: () -> Boolean) {
        if (owner() != expectedOwner || ProfileRepository.activeProfileId != profile || !isCurrent()) {
            throw CancellationException("MyAnimeList account changed")
        }
    }

    suspend fun push(tokens: MalTokens, profile: Int, isCurrent: () -> Boolean): String {
        val owner = owner() ?: error("Sign in to Starlit before syncing MyAnimeList to TVs.")
        val session = captureSession(owner)
        requireScope(owner, profile, isCurrent)
        boundMalAccountRpc(session, "sync_push_provider_credentials", buildJsonObject {
            put("p_profile_id", profile)
            put("p_credentials", buildJsonArray { add(buildJsonObject {
                put("provider", "myanimelist")
                put("credential_json", malAccountCredential(tokens))
            }) })
        })
        requireScope(owner, profile, isCurrent)
        return owner
    }

    suspend fun pull(profile: Int, expectedOwner: String, isCurrent: () -> Boolean): MalTokens? {
        val session = captureSession(expectedOwner)
        requireScope(expectedOwner, profile, isCurrent)
        val rows = boundMalAccountRpc(session, "sync_pull_provider_credentials", buildJsonObject {
            put("p_profile_id", profile)
        }).jsonArray.map { it.jsonObject }
        requireScope(expectedOwner, profile, isCurrent)
        val row = rows.firstOrNull { it["provider"]?.jsonPrimitive?.contentOrNull == "myanimelist" }
        return (row?.get("credential_json") as? JsonObject)?.let(::parseMalAccountCredential)
    }
}

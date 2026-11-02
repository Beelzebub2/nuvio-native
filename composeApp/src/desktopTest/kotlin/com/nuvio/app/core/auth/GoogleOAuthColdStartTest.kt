package com.nuvio.app.core.auth

import com.nuvio.app.core.network.ServerCapabilities
import com.nuvio.app.core.network.ServerConfiguration
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import com.sun.net.httpserver.HttpServer
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.minimalSettings
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleOAuthColdStartTest {
    @Test
    fun verifiedCallbackRestoresIntentAfterRestartWithoutSelectingACachedAccount() = runBlocking {
        val guestId = "86e37ca2-76e5-46b1-9fd5-b6f721aeaaab"
        val accountId = "9b16ea29-436d-4b73-aa8d-63dbf78c5ef1"
        val user = """{"id":"$accountId","aud":"authenticated","email":"approved@example.com","app_metadata":{},"user_metadata":{},"created_at":"2026-01-01T00:00:00Z"}"""
        val session = """{"access_token":"test-access-token","refresh_token":"test-refresh-token","token_type":"bearer","expires_in":3600,"user":$user}"""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val code = if (exchange.requestURI.path.endsWith("/token")) {
                Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText())
                    .jsonObject["auth_code"]?.jsonPrimitive?.content
            } else null
            val status = if (code == "invalid-code") 400 else 200
            val body = when {
                status == 400 -> """{"error_code":"invalid_grant","msg":"Invalid code"}"""
                code != null -> session
                else -> user
            }.toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        val client = createSupabaseClient("http://127.0.0.1:${server.address.port}", "sb_publishable_test") {
            install(Auth) {
                minimalSettings()
                flowType = FlowType.PKCE
            }
        }
        val originalServer = ServerConfigurationRepository.active.value
        val originalGuest = AuthStorage.loadAnonymousUserId()
        val cachedClient = SupabaseProvider.javaClass.getDeclaredField("cachedClient").apply { isAccessible = true }
        val originalClient = cachedClient.get(SupabaseProvider)
        try {
            assertTrue(ServerConfigurationRepository.saveCustom(ServerConfiguration(
                STARLIT_GOOGLE_ORIGIN, "sb_publishable_test", ServerCapabilities(true, false), true)))
            cachedClient.set(SupabaseProvider, client)
            client.auth.awaitInitialization()
            // A restarted app may still have an old remote session beneath its guest workspace.
            client.auth.importSession(Json.decodeFromString<UserSession>(session.replace(accountId, guestId)))
            AuthStorage.saveAnonymousUserId(guestId)
            AuthRepository.cancelRemoteSessionRequest()
            AuthRepository.reinitialize()
            assertTrue((AuthRepository.state.value as AuthState.Authenticated).isAnonymous)
            client.auth.codeVerifierCache.saveCodeVerifier("persisted-pkce-verifier")

            AuthRepository.handleGoogleOAuthCallback("nuvio://auth?code=invalid-code")
            withTimeout(5_000) { AuthRepository.error.first { it != null } }
            assertTrue((AuthRepository.state.value as AuthState.Authenticated).isAnonymous)

            AuthRepository.handleGoogleOAuthCallback("nuvio://auth?code=verified-code")
            val accepted = withTimeout(5_000) {
                AuthRepository.state.first { it is AuthState.Authenticated && !it.isAnonymous }
            } as AuthState.Authenticated
            assertEquals(accountId, accepted.userId)
            assertNull(AuthStorage.loadAnonymousUserId())
        } finally {
            client.auth.clearSession()
            AuthRepository.cancelRemoteSessionRequest()
            if (originalGuest == null) AuthStorage.clearAnonymousUserId() else AuthStorage.saveAnonymousUserId(originalGuest)
            cachedClient.set(SupabaseProvider, originalClient)
            if (originalServer.isCustom) ServerConfigurationRepository.saveCustom(originalServer)
            else ServerConfigurationRepository.useOfficial()
            AuthRepository.reinitialize()
            client.close()
            server.stop(0)
        }
    }
}

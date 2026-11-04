package com.nuvio.app.core.auth

import com.sun.net.httpserver.HttpServer
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

internal actual suspend fun launchGoogleOAuth(client: SupabaseClient) {
    val code = awaitGoogleAuthorization(
        authorize = { client.auth.getOAuthUrl(Google, redirectUrl = it) },
        open = { Desktop.getDesktop().browse(it) },
    )
    AuthRepository.completeGoogleOAuth(client, code)
}

internal actual suspend fun launchGoogleAccountLink(password: String, openUrl: (String) -> Unit) {
    val client = com.nuvio.app.core.network.SupabaseProvider.client
    try {
        val code = awaitGoogleAuthorization(
            authorize = { AuthRepository.prepareGoogleAccountLink(password, it).getOrThrow() },
            open = { openUrl(it.toString()) },
        )
        AuthRepository.completeGoogleOAuth(client, code)
    } catch (error: Throwable) {
        AuthRepository.cancelGoogleAccountLink()
        throw error
    }
}

private fun googleAuthorizationFailure(error: String): GoogleAuthorizationFailure = GoogleAuthorizationFailure(
    when (error) {
        "access_denied" -> "Google authorization was declined. Try again if you want to connect Google."
        "server_error", "temporarily_unavailable" -> "Google authorization is temporarily unavailable. Try again shortly."
        "invalid_request", "unauthorized_client", "invalid_scope", "unsupported_response_type" ->
            "Google rejected the authorization request. Try again or contact support."
        else -> "Google authorization failed. Try again."
    },
)

/** Invalid or unrelated callbacks cannot complete the pending authorization. */
private fun parseGoogleAuthorizationCallback(request: URI, port: Int, path: String, state: String): Result<String>? =
    runCatching {
        require(!request.isAbsolute && request.rawPath == path && request.rawFragment == null)
        require(request.toString().length <= 8192)
        val url = Url("http://127.0.0.1:$port$request")
        require(url.encodedPath == path && url.fragment.isEmpty())
        val params = url.parameters
        val callbackState = requireNotNull(params.getAll("state")?.singleOrNull())
        require(MessageDigest.isEqual(callbackState.toByteArray(Charsets.UTF_8), state.toByteArray(Charsets.UTF_8)))

        when (val names = params.names()) {
            setOf("state", "code") -> {
                val code = requireNotNull(params.getAll("code")?.singleOrNull()?.takeIf {
                    it.isNotBlank() && it.length <= 4096
                })
                Result.success(code)
            }
            else -> {
                require("error" in names && names.all {
                    it in setOf("state", "error", "error_description", "error_code", "error_uri")
                })
                val error = requireNotNull(params.getAll("error")?.singleOrNull()?.takeIf {
                    it.isNotBlank() && it.length <= 128
                })
                require(listOf("error_description", "error_code", "error_uri").all { field ->
                    params.getAll(field).orEmpty().let { it.size <= 1 && it.all { value -> value.length <= 1024 } }
                })
                Result.failure<String>(googleAuthorizationFailure(error))
            }
        }
    }.getOrNull()

/** Use a private loopback listener and a request-specific state; never accept implicit access/refresh tokens. */
internal suspend fun awaitGoogleAuthorization(
    authorize: suspend (String) -> String,
    open: (URI) -> Unit,
): String = withContext(Dispatchers.IO) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val path = "/auth/callback/${UUID.randomUUID()}"
    val state = UUID.randomUUID().toString()
    val redirect = "http://127.0.0.1:${server.address.port}$path?state=$state"
    val result = CompletableDeferred<String>()
    server.createContext(path) { exchange ->
        val callback = if (exchange.requestMethod == "GET") {
            parseGoogleAuthorizationCallback(exchange.requestURI, server.address.port, path, state)
        } else null
        val message = when {
            callback?.isSuccess == true -> "Authorization received. Return to Starlit."
            callback?.exceptionOrNull() is GoogleAuthorizationFailure ->
                "${callback.exceptionOrNull()?.message} Return to Starlit."
            else -> "Invalid authorization callback."
        }
        val bytes = message.toByteArray(Charsets.UTF_8)
        try {
            exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.sendResponseHeaders(if (callback?.isSuccess == true) 200 else 400, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } finally {
            callback?.onSuccess { result.complete(it) }
            callback?.exceptionOrNull()?.let { result.completeExceptionally(it) }
            exchange.close()
        }
    }
    try {
        server.start()
        open(URI(authorize(redirect)))
        withTimeout(5 * 60_000L) { result.await() }
    } finally {
        server.stop(0)
    }
}

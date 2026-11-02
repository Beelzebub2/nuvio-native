package com.nuvio.app.core.auth

import com.nuvio.app.core.network.SupabaseProvider
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
import java.util.UUID

internal actual suspend fun launchGoogleOAuth(client: SupabaseClient) {
    val code = awaitGoogleAuthorization(
        authorize = { client.auth.getOAuthUrl(Google, redirectUrl = it) },
        open = { Desktop.getDesktop().browse(it) },
    )
    check(GoogleOAuthAvailability.isOfficialServer() && SupabaseProvider.client === client) {
        "The account server changed during Google sign-in"
    }
    val session = client.auth.exchangeCodeForSession(code, saveSession = false)
    check(GoogleOAuthAvailability.isOfficialServer() && SupabaseProvider.client === client) {
        "The account server changed during Google verification"
    }
    client.auth.importSession(session)
}

/** Use a private loopback listener; never accept implicit access/refresh tokens. */
internal suspend fun awaitGoogleAuthorization(
    authorize: (String) -> String,
    open: (URI) -> Unit,
): String = withContext(Dispatchers.IO) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val path = "/auth/callback/${UUID.randomUUID()}"
    val redirect = "http://127.0.0.1:${server.address.port}$path"
    val result = CompletableDeferred<String>()
    server.createContext(path) { exchange ->
        val code = runCatching {
            require(exchange.requestMethod == "GET" && exchange.requestURI.toString().length <= 8192)
            val url = Url("http://127.0.0.1:${server.address.port}${exchange.requestURI}")
            require(url.encodedPath == path && url.fragment.isEmpty())
            require(url.parameters.names() == setOf("code"))
            requireNotNull(url.parameters.getAll("code")?.singleOrNull()?.takeIf {
                it.isNotBlank() && it.length <= 4096
            })
        }.getOrNull()
        val bytes = (if (code != null) "Authorization received. Return to Starlit."
            else "Invalid authorization callback.").toByteArray(Charsets.UTF_8)
        try {
            exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.sendResponseHeaders(if (code != null) 200 else 400, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } finally {
            if (code != null) result.complete(code)
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

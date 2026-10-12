package com.nuvio.app.features.myanimelist

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal const val MAL_REDIRECT = "http://127.0.0.1:54328/myanimelist/callback"
internal fun malNonce(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64).also { SecureRandom().nextBytes(it) })
internal fun malAuthorizationUrl(clientId: String, state: String, verifier: String): String =
    "https://myanimelist.net/v1/oauth2/authorize".toHttpUrl().newBuilder()
        .addQueryParameter("response_type", "code").addQueryParameter("client_id", clientId)
        .addQueryParameter("redirect_uri", MAL_REDIRECT).addQueryParameter("state", state)
        .addQueryParameter("code_challenge", verifier).addQueryParameter("code_challenge_method", "plain").build().toString()

internal fun malCallbackCode(url: String, state: String): String {
    require(url.length <= 8192)
    val parsed = url.toHttpUrl()
    require(parsed.scheme == "http" && parsed.host == "127.0.0.1" && parsed.port == 54328 && parsed.encodedPath == "/myanimelist/callback")
    val states = parsed.queryParameterValues("state")
    require(states.size == 1 && MessageDigest.isEqual(states.single().orEmpty().toByteArray(), state.toByteArray())) { "Invalid MyAnimeList sign-in state" }
    require(parsed.queryParameter("error") == null) { "MyAnimeList sign-in was declined" }
    val codes = parsed.queryParameterValues("code")
    return codes.singleOrNull()?.takeIf { it.isNotBlank() && it.length <= 4096 } ?: error("Missing MyAnimeList authorization code")
}
internal suspend fun awaitMalAuthorization(clientId: String, open: (URI) -> Unit): Pair<String, String> {
    val state = malNonce()
    val verifier = malNonce()
    val result = CompletableDeferred<String>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 54328), 0)
    server.createContext("/myanimelist/callback") { exchange ->
        val code = runCatching {
            require(exchange.requestMethod == "GET")
            malCallbackCode("http://127.0.0.1:54328${exchange.requestURI}", state)
        }
        val message = if (code.isSuccess) "Authorization received. Return to Nuvio Native to finish connecting." else "Sign-in was declined or invalid. Return to Nuvio Native and retry."
        val bytes = message.toByteArray(Charsets.UTF_8)
        try {
            exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
            exchange.sendResponseHeaders(if (code.isSuccess) 200 else 400, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } finally {
            code.onSuccess { result.complete(it) }
            // An unrelated local request must not abort the user's valid sign-in.
            if (exchange.requestURI.rawQuery?.contains("error=access_denied") == true && code.exceptionOrNull()?.message == "MyAnimeList sign-in was declined")
                result.completeExceptionally(IllegalStateException("MyAnimeList sign-in was declined"))
            exchange.close()
        }
    }
    try { server.start(); open(URI(malAuthorizationUrl(clientId, state, verifier))); return withTimeout(5 * 60_000L) { result.await() } to verifier }
    finally { server.stop(0) }
}

package com.nuvio.app.core.auth

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class GoogleOAuthDesktopTest {
    @Test
    fun cancelingAuthorizationClosesItsPrivateListener() = runBlocking {
        val opened = CompletableDeferred<URI>()
        val request = launch {
            awaitGoogleAuthorization(authorize = { redirect -> delay(1); redirect }, open = { opened.complete(it) })
        }
        val redirect = opened.await()
        request.cancelAndJoin()
        assertFails {
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(redirect).GET().build(),
                HttpResponse.BodyHandlers.discarding())
        }
    }

    @Test
    fun loopbackCallbackRejectsTokensDuplicateCodesAndOtherPaths() = runBlocking {
        val http = HttpClient.newHttpClient()
        fun status(url: String): Int = http.send(HttpRequest.newBuilder(URI(url)).GET().build(),
            HttpResponse.BodyHandlers.discarding()).statusCode()
        val code = awaitGoogleAuthorization(authorize = { it }, open = { redirect ->
            assertEquals("127.0.0.1", redirect.host)
            assertEquals(400, status("$redirect?access_token=injected&refresh_token=injected"))
            assertEquals(400, status("$redirect?code=one&code=two"))
            assertEquals(400, status("$redirect/other?code=wrong-path"))
            assertEquals(404, status("http://127.0.0.1:${redirect.port}/callback?code=injected"))
            assertEquals(200, status("$redirect?code=verified-authorization-code"))
        })
        assertEquals("verified-authorization-code", code)
    }
}

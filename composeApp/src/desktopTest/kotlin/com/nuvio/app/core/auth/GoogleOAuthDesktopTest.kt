package com.nuvio.app.core.auth

import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleOAuthDesktopTest {
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

package com.nuvio.app.core.auth

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoogleOAuthDesktopTest {
    private val http = HttpClient.newHttpClient()

    private fun response(url: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofString())

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
        Unit
    }

    @Test
    fun loopbackCallbackRequiresMatchingStateAndRejectsTokensDuplicateCodesAndOtherPaths() = runBlocking {
        val code = awaitGoogleAuthorization(authorize = { it }, open = { redirect ->
            assertEquals("127.0.0.1", redirect.host)
            val state = Url(redirect.toString()).parameters.getAll("state")!!.single()
            assertTrue(state.isNotBlank())
            val base = redirect.toString().substringBefore('?')
            assertEquals(400, response("$redirect&access_token=injected&refresh_token=injected").statusCode())
            assertEquals(400, response("$redirect&code=one&code=two").statusCode())
            assertEquals(400, response("$base/other?state=$state&code=wrong-path").statusCode())
            assertEquals(404, response("http://127.0.0.1:${redirect.port}/callback?state=$state&code=injected").statusCode())
            assertEquals(400, response("$base?code=missing-state").statusCode())
            assertEquals(400, response("$base?state=wrong&code=wrong-state").statusCode())
            assertEquals(400, response("$redirect&state=$state&code=duplicate-state").statusCode())
            assertEquals(400, response("$redirect&error=access_denied&code=mixed-response").statusCode())
            assertEquals(200, response("$redirect&code=verified-authorization-code").statusCode())
        })
        assertEquals("verified-authorization-code", code)
    }

    @Test
    fun validStateDenialEndsImmediatelyWithSafeClearFeedback() = runBlocking {
        val failure = assertFailsWith<GoogleAuthorizationFailure> {
            withTimeout(3_000L) {
                awaitGoogleAuthorization(authorize = { it }, open = { redirect ->
                    val denied = response("$redirect&error=access_denied&error_description=do-not-echo-me")
                    assertEquals(400, denied.statusCode())
                    assertContains(denied.body(), "declined")
                    assertFalse("do-not-echo-me" in denied.body())
                })
            }
        }
        assertContains(failure.message.orEmpty(), "declined")
        assertFalse("do-not-echo-me" in failure.message.orEmpty())
    }

    @Test
    fun providerErrorsEndPromptlyWithoutExposingUntrustedDescriptions() = runBlocking {
        for ((providerError, expected) in listOf(
            "server_error" to "temporarily unavailable",
            "temporarily_unavailable" to "temporarily unavailable",
            "invalid_request" to "rejected",
            "unexpected_provider_failure" to "authorization failed",
        )) {
            val failure = assertFailsWith<GoogleAuthorizationFailure> {
                withTimeout(3_000L) {
                    awaitGoogleAuthorization(authorize = { it }, open = { redirect ->
                        val result = response("$redirect&error=$providerError&error_code=fixture&error_description=secret-detail")
                        assertEquals(400, result.statusCode())
                        assertContains(result.body(), expected)
                        assertFalse("secret-detail" in result.body())
                    })
                }
            }
            assertContains(failure.message.orEmpty(), expected)
        }
    }

    @Test
    fun forgedOrMalformedDenialsCannotInterruptAValidAuthorization() = runBlocking {
        val code = withTimeout(3_000L) {
            awaitGoogleAuthorization(authorize = { it }, open = { redirect ->
                val state = Url(redirect.toString()).parameters.getAll("state")!!.single()
                val base = redirect.toString().substringBefore('?')
                assertEquals(400, response("$base?error=access_denied").statusCode())
                assertEquals(400, response("$base?state=wrong&error=access_denied").statusCode())
                assertEquals(400, response("$redirect&state=$state&error=access_denied").statusCode())
                assertEquals(400, response("$redirect&error=access_denied&error=server_error").statusCode())
                assertEquals(400, response("$redirect&error=access_denied&access_token=injected").statusCode())
                assertEquals(400, response("$redirect&error=access_denied&error_uri=http://example.com&refresh_token=injected").statusCode())
                assertEquals(200, response("$redirect&code=legitimate-code").statusCode())
            })
        }
        assertEquals("legitimate-code", code)
    }
}

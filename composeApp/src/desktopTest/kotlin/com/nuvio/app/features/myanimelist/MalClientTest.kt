package com.nuvio.app.features.myanimelist

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.BindException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import org.junit.Test
import org.junit.Assume.assumeTrue
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.*

class MalClientTest {
    private fun callbackResponse(listener: HttpServer, query: String): Int {
        val connection = URL("http://127.0.0.1:${listener.address.port}/myanimelist/callback?$query").openConnection() as HttpURLConnection
        connection.connectTimeout = 1500
        connection.readTimeout = 1500
        return try { connection.responseCode } finally { connection.disconnect() }
    }

    private suspend fun testAuthorization(open: (URI, HttpServer) -> Unit): Pair<String, String> {
        val listener = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        return awaitMalAuthorization("test-client-id", createListener = { listener }) { authorization ->
            // Only the local transport is replaced; MAL still receives its registered redirect.
            assertEquals(MAL_REDIRECT, authorization.toString().toHttpUrl().queryParameter("redirect_uri"))
            open(authorization, listener)
        }
    }

    @Test fun statusAndScoreUpdateTheRealApiFieldsWithoutOverwritingEpisodeProgress(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var status = "watching"
        var score = 8
        val updates = mutableListOf<Map<String, String>>()
        server.createContext("/v2/anime/21") { request ->
            assertEquals("Bearer test-token", request.requestHeaders.getFirst("Authorization"))
            val payload = if (request.requestMethod == "PATCH") {
                assertEquals("/v2/anime/21/my_list_status", request.requestURI.path)
                val fields = request.requestBody.readBytes().toString(Charsets.UTF_8).split('&').associate {
                    val pair = it.split('=', limit = 2); pair[0] to URLDecoder.decode(pair[1], "UTF-8") }
                updates += fields
                fields["status"]?.let { status = it }; fields["score"]?.let { score = it.toInt() }
                """{"status":"$status","score":$score,"num_episodes_watched":7}"""
            } else """{"my_list_status":{"status":"$status","score":$score,"num_episodes_watched":7}}"""
            val bytes = payload.toByteArray()
            request.sendResponseHeaders(200, bytes.size.toLong()); request.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = MalClient(api = "http://127.0.0.1:${server.address.port}/v2/")
            assertEquals(MalStatus.WATCHING, client.listStatus(21, "test-token")?.status)
            val dropped = client.update(21, "test-token", status = MalStatus.DROPPED)
            assertEquals(MalStatus.DROPPED, dropped.status); assertEquals(8, dropped.score)
            val rated = client.update(21, "test-token", score = 9)
            assertEquals(MalStatus.DROPPED, rated.status); assertEquals(9, rated.score); assertEquals(7, rated.watchedEpisodes)
            assertEquals(listOf(mapOf("status" to "dropped"), mapOf("score" to "9")), updates)
            assertEquals(0, client.update(21, "test-token", score = 0).score)
            assertFailsWith<IllegalArgumentException> { client.update(21, "test-token", score = 11) }
        } finally { server.stop(0) }
    }
    @Test fun oauthUsesMalPkceAndRejectsWrongOrDuplicatedState() {
        val verifier = malNonce()
        val state = malNonce()
        val url = malAuthorizationUrl("test-client-id", state, verifier).toHttpUrl()
        assertEquals("http://127.0.0.1:54328/myanimelist/callback", url.queryParameter("redirect_uri"))
        assertEquals(verifier, url.queryParameter("code_challenge"))
        assertEquals("plain", url.queryParameter("code_challenge_method"))
        assertTrue(verifier.length in 43..128)
        assertEquals("test-code", malCallbackCode("$MAL_REDIRECT?state=$state&code=test-code", state))
        assertFailsWith<IllegalArgumentException> { malCallbackCode("$MAL_REDIRECT?state=wrong&code=test-code", state) }
        assertFailsWith<IllegalArgumentException> { malCallbackCode("$MAL_REDIRECT?state=$state&state=$state&code=test-code", state) }
        assertFailsWith<IllegalArgumentException> { malCallbackCode("https://example.com/myanimelist/callback?state=$state&code=test-code", state) }
        assertFailsWith<IllegalArgumentException> { malCallbackCode("http://127.0.0.1:54329/myanimelist/callback?state=$state&code=test-code", state) }
        assertFailsWith<IllegalArgumentException> { malCallbackCode("$MAL_REDIRECT/extra?state=$state&code=test-code", state) }
    }
    @Test fun validStateAuthorizationErrorsCompleteImmediatelyInsteadOfTimingOut(): Unit = runBlocking {
        for ((errorCode, expected) in listOf("access_denied" to "declined", "access%5Fdenied" to "declined", "server_error" to "server_error")) {
            val failure = assertFailsWith<Exception> {
                withTimeout(2_000L) {
                    testAuthorization { authorization, listener ->
                        val state = authorization.toString().toHttpUrl().queryParameter("state")!!
                        assertEquals(400, callbackResponse(listener, "state=$state&error=$errorCode"))
                    }
                }
            }
            assertContains(failure.message.orEmpty(), expected)
        }
    }
    @Test fun wrongStateCallbackCannotEndLoginButAValidCodeStillWorks(): Unit = runBlocking {
        val (code, verifier) = withTimeout(2_000L) {
            testAuthorization { authorization, listener ->
                val state = authorization.toString().toHttpUrl().queryParameter("state")!!
                assertEquals(400, callbackResponse(listener, "state=wrong&error=access_denied"))
                assertEquals(200, callbackResponse(listener, "state=$state&code=valid-code"))
            }
        }
        assertEquals("valid-code", code)
        assertTrue(verifier.length in 43..128)
    }
    @Test fun missingAuthorizationCodeWithValidStateReportsErrorImmediately(): Unit = runBlocking {
        val failure = assertFailsWith<Exception> {
            withTimeout(2_000L) {
                testAuthorization { authorization, listener ->
                    val state = authorization.toString().toHttpUrl().queryParameter("state")!!
                    assertEquals(400, callbackResponse(listener, "state=$state"))
                }
            }
        }
        assertContains(failure.message.orEmpty(), "authorization code")
    }
    @Test fun occupiedCallbackPortProducesActionableError(): Unit = runBlocking {
        val occupied = BindException("Fixture: callback port is occupied")
        val failure = assertFailsWith<IllegalStateException> {
            awaitMalAuthorization("test-client-id", createListener = { throw occupied }) {
                error("Browser must not open without a listener")
            }
        }
        assertSame(occupied, failure.cause)
        assertContains(failure.message.orEmpty(), "54328")
        assertContains(failure.message.orEmpty(), "already in use")
    }
    @Test fun independentCallbackListenersDoNotSharePortsOrAuthorizationState(): Unit = runBlocking {
        val listenersReady = java.util.concurrent.CountDownLatch(3)
        val ports = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val codes = withTimeout(5_000L) {
            coroutineScope {
                (1..3).map { index -> async(Dispatchers.IO) {
                    testAuthorization { authorization, listener ->
                        assertTrue(ports.add(listener.address.port))
                        listenersReady.countDown()
                        assertTrue(listenersReady.await(2, java.util.concurrent.TimeUnit.SECONDS))
                        val state = authorization.toString().toHttpUrl().queryParameter("state")!!
                        assertEquals(400, callbackResponse(listener, "state=wrong&code=ignored"))
                        assertEquals(200, callbackResponse(listener, "state=$state&code=code-$index"))
                    }.first
                } }.awaitAll()
            }
        }
        assertEquals(setOf("code-1", "code-2", "code-3"), codes.toSet())
        assertEquals(3, ports.size)
    }
    @Test fun oauthTokensSupportPublicAndWebClientsWithoutLosingTheSecretOnRefresh(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<Map<String, String>>()
        server.createContext("/oauth/token") { request ->
            val fields = request.requestBody.readBytes().toString(Charsets.UTF_8).split('&').associate {
                val pair = it.split('=', limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
            }
            requests += fields
            val status = if (fields["client_id"] == "web-id" && fields["client_secret"] != "web-secret") 401 else 200
            val payload = if (status == 200)
                """{"access_token":"access","refresh_token":"refresh","expires_in":3600}"""
            else """{"error":"invalid_client","message":"A client secret is required"}"""
            val bytes = payload.toByteArray()
            request.sendResponseHeaders(status, bytes.size.toLong())
            request.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = MalClient(oauth = "http://127.0.0.1:${server.address.port}/oauth/")
            val public = client.exchange("other-id", "code", "verifier", MAL_REDIRECT)
            assertEquals("", public.clientSecret)
            assertFalse(requests.last().containsKey("client_secret"))
            val invalid = assertFailsWith<MalHttpException> {
                client.exchange("web-id", "code", "verifier", MAL_REDIRECT)
            }
            assertEquals(401, invalid.status)
            assertContains(invalid.message.orEmpty(), "client secret")
            val web = client.exchange("web-id", "code", "verifier", MAL_REDIRECT, "web-secret")
            assertEquals("web-secret", web.clientSecret)
            assertEquals("web-secret", requests.last()["client_secret"])
            val renewed = client.refresh(web)
            assertEquals("web-secret", renewed.clientSecret)
            assertEquals("web-secret", requests.last()["client_secret"])
            assertEquals("refresh_token", requests.last()["grant_type"])
        } finally { server.stop(0) }
    }
    @Test fun windowsCredentialsAreEncryptedAndRoundTripUnderTheCurrentUser() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val original = "fake-access-token-for-test-only"
        val encrypted = protectMalCredential(original)
        assertFalse(encrypted.contains(original))
        assertEquals(original, protectMalCredential(encrypted, decrypt = true))
        val token = MalTokens("client-id", "access-token", "refresh-token", 100L, clientSecret = "example-web-secret")
        val payload = Json.encodeToString(token)
        val protected = protectMalCredential(payload)
        assertFalse(protected.contains(token.clientSecret))
        assertEquals(token, Json.decodeFromString<MalTokens>(protectMalCredential(protected, decrypt = true)))
        val legacy = """{"clientId":"client-id","accessToken":"access-token","refreshToken":"refresh-token","expiresAt":100}"""
        assertEquals("", Json.decodeFromString<MalTokens>(legacy).clientSecret)
    }
}

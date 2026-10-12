package com.nuvio.app.features.myanimelist

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.net.URLDecoder
import org.junit.Test
import org.junit.Assume.assumeTrue
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.*

class MalClientTest {
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
        assertEquals(verifier, url.queryParameter("code_challenge"))
        assertEquals("plain", url.queryParameter("code_challenge_method"))
        assertTrue(verifier.length in 43..128)
        assertEquals("test-code", malCallbackCode("$MAL_REDIRECT?state=$state&code=test-code", state))
        assertFailsWith<IllegalArgumentException> { malCallbackCode("$MAL_REDIRECT?state=wrong&code=test-code", state) }
        assertFailsWith<IllegalArgumentException> { malCallbackCode("$MAL_REDIRECT?state=$state&state=$state&code=test-code", state) }
        assertFailsWith<IllegalArgumentException> { malCallbackCode("https://example.com/myanimelist/callback?state=$state&code=test-code", state) }
    }
    @Test fun windowsCredentialsAreEncryptedAndRoundTripUnderTheCurrentUser() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val original = "fake-access-token-for-test-only"
        val encrypted = protectMalCredential(original)
        assertFalse(encrypted.contains(original))
        assertEquals(original, protectMalCredential(encrypted, decrypt = true))
    }
}

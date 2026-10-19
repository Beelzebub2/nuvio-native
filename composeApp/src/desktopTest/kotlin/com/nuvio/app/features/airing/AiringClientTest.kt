package com.nuvio.app.features.airing

import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AiringClientTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private val emptySchedule = """{"data":{"Page":{"pageInfo":{"hasNextPage":false},"airingSchedules":[]}}}"""
    private fun response(request: Request, payload: String, code: Int = 200, retry: String? = null) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(payload.toResponseBody()).apply { retry?.let { header("Retry-After", it) } }.build()
    private fun http(handler: (Request) -> Response) = OkHttpClient.Builder()
        .addInterceptor { handler(it.request()) }.build()

    @Test fun `gateway rate limits do not fall through to direct and share a provider cooldown`() = runBlocking {
        for (code in listOf(429, 503)) {
            var clock = now.toEpochMilli()
            val requests = mutableListOf<Request>()
            val client = AiringClient(http { request ->
                requests += request
                if (requests.size == 1) response(request, """{"providerStatus":429}""", code, "180")
                else response(request, emptySchedule)
            }, gateway = "https://gateway.test", clock = { clock })
            repeat(2) {
                try { client.schedule(AiringSettings(), now); fail("Must respect provider limit") }
                catch (e: AiringRateLimitException) { assertEquals(now.plusSeconds(180).toEpochMilli(), e.retryAt) }
                clock += 90_000
            }
            assertEquals(1, requests.size)
            client.schedule(AiringSettings(), now)
            assertEquals(3, requests.size)
            assertTrue(requests.all { it.url.host == "gateway.test" })
        }
    }

    @Test fun `gateway outage retains direct fallback and stable UTC windows ignore history controls`() = runBlocking {
        val requests = mutableListOf<Request>()
        var clock = now.toEpochMilli()
        val client = AiringClient(http { request ->
            requests += request; clock += 3000
            if (request.url.host == "gateway.test") response(request, """{"providerStatus":403}""", 503, "60")
            else response(request, emptySchedule)
        }, gateway = "https://gateway.test", clock = { clock })
        client.schedule(AiringSettings(historyDays = 1, zone = "Pacific/Kiritimati"), now)
        client.schedule(AiringSettings(historyDays = 5, zone = "America/New_York"), now)
        val gatewayRequests = requests.filter { it.url.host == "gateway.test" }
        assertEquals(1, gatewayRequests.size)
        assertEquals(4, requests.count { it.url.host == "graphql.anilist.co" })
        assertEquals("/schedule-recent", gatewayRequests.single().url.encodedPath)
        val direct = requests.filter { it.url.host == "graphql.anilist.co" }
        fun body(request: Request) = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertEquals(direct.take(2).map(::body), direct.drop(2).map(::body))
        assertTrue(body(direct[0]).contains("notYetAired:false,sort:TIME_DESC"))
        assertTrue(body(direct[1]).contains("notYetAired:true,sort:TIME"))
    }

    @Test fun `release schedules bulk classify once and never trust adult exclusion for Ecchi`() = runBlocking {
        var classifications = 0
        val client = AiringClient(http { request ->
            val payload = if (request.url.encodedPath == "/classifications") {
                classifications++
                assertEquals("31,218217", request.url.queryParameter("ids"))
                """{"data":{"Page":{"media":[{"id":31,"isAdult":false,"genres":[],"tags":[]},{"id":218217,"isAdult":false,"genres":["Ecchi"],"tags":[]}]}}}"""
            } else """{"episodes":[{"mediaId":31,"episode":1,"airingAt":1792321200,"airType":"sub","isAdult":false},{"mediaId":218217,"episode":1,"airingAt":1792321200,"airType":"sub","isAdult":false}]}"""
            response(request, payload)
        }, gateway = "https://gateway.test", clock = { now.toEpochMilli() })
        val settings = AiringSettings(airType = "sub")
        repeat(2) {
            assertEquals(setOf(31), filterAiringContent(client.schedule(settings, now), settings).map { it.mediaId }.toSet())
        }
        assertEquals(1, classifications)
    }

    @Test fun `stale gateway pages finish a multipage schedule while direct access stays blocked`() = runBlocking {
        var requests = 0
        var clock = now.toEpochMilli()
        val client = AiringClient(http { request ->
            requests++; clock += 3000
            assertEquals("gateway.test", request.url.host)
            val more = request.url.queryParameter("page") == "1"
            val payload = """{"data":{"Page":{"pageInfo":{"hasNextPage":$more},"airingSchedules":[]}}}"""
            response(request, payload).newBuilder().header("X-Nuvio-Stale", "1").header("Retry-After", "180").build()
        }, gateway = "https://gateway.test", clock = { clock })
        assertTrue(client.schedule(AiringSettings(), now).isEmpty())
        assertEquals(4, requests)
    }

    @Test fun `details reject fresh Ecchi metadata and independent providers remain parallel`() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(2)
        val client = AiringClient(http { request ->
            if (request.url.encodedPath in listOf("/media", "/episodes")) {
                entered.countDown()
                assertTrue("Independent providers should execute concurrently", entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            }
            val payload = when {
                request.url.encodedPath == "/media" -> """{"data":{"Media":{"id":218217,"isAdult":false,"genres":["Ecchi"],"tags":[]}}}"""
                request.url.host == "raw.githubusercontent.com" -> "[]"
                else -> "{}"
            }
            response(request, payload)
        }, gateway = "https://gateway.test", clock = { now.toEpochMilli() })
        val known = listOf(AiringEvent(218217, 1, now.epochSecond, "Example", isAdult = false))
        assertNull(client.details(218217, known, AiringSettings()))
    }

    @Test fun `gateway outage memo expires and does not disable independent schedule providers`() = runBlocking {
        val clock = java.util.concurrent.atomic.AtomicLong(now.toEpochMilli())
        var aniListGateway = 0
        var releaseGateway = 0
        val client = AiringClient(http { request ->
            when {
                request.url.host == "graphql.anilist.co" -> response(request, emptySchedule)
                request.url.encodedPath.startsWith("/release-schedule") -> {
                    releaseGateway++; response(request, """{"episodes":[]}""")
                }
                else -> { aniListGateway++; response(request, """{"providerStatus":403}""", 503, "60") }
            }
        }, gateway = "https://gateway.test", clock = clock::get, pause = { clock.addAndGet(it) })
        client.schedule(AiringSettings(), now)
        client.schedule(AiringSettings(airType = "sub"), now)
        assertEquals(1, aniListGateway)
        assertEquals(2, releaseGateway)
        clock.addAndGet(60_000L)
        client.schedule(AiringSettings(), now)
        assertEquals(2, aniListGateway)
    }

    @Test fun `retry after seconds dates and provider reset use the latest deadline`() {
        val clock = now.toEpochMilli()
        assertEquals(clock + 180_000, retryTime(Headers.headersOf("Retry-After", "180"), clock))
        assertEquals(clock + 300_000, retryTime(Headers.headersOf("Retry-After", "Sun, 18 Oct 2026 12:05:00 GMT"), clock))
        assertEquals(clock + 600_000, retryTime(Headers.headersOf("Retry-After", "120", "X-RateLimit-Reset", (now.epochSecond + 600).toString()), clock))
        assertEquals(clock + 60_000, retryTime(Headers.headersOf("Retry-After", "invalid"), clock))
    }
}

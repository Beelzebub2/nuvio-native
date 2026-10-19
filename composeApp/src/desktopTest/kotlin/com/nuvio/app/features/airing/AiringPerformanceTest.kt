package com.nuvio.app.features.airing

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class AiringPerformanceTest {
    private val now = Instant.parse("2026-10-19T12:00:00Z")

    @Test fun `cold failed gateway yields recent results before backfill with one failed attempt`() = runBlocking {
        val elapsed = AtomicLong()
        var gatewayCalls = 0
        var directCalls = 0
        var firstBatchAt = -1L
        val recent = now.minusSeconds(3600).epochSecond
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val gateway = request.url.host == "gateway.test"
            val payload: String
            if (gateway) {
                gatewayCalls++; elapsed.addAndGet(750)
                payload = """{"providerStatus":403}"""
            } else {
                directCalls++; elapsed.addAndGet(550)
                val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                val lastPage = if (body.contains("TIME_DESC")) 3 else 2
                val page = Json.parseToJsonElement(body).jsonObject["variables"]!!.jsonObject["page"]!!.jsonPrimitive.int
                val more = page < lastPage
                payload = """{"data":{"Page":{"pageInfo":{"hasNextPage":$more},"airingSchedules":[{"episode":$page,"airingAt":$recent,"media":{"id":$directCalls,"isAdult":false,"genres":[],"tags":[]}}]}}}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (gateway) 503 else 200)
                .message("fixture").body(payload.toResponseBody()).build()
        }.build()
        val client = AiringClient(http, "https://gateway.test", clock = { now.toEpochMilli() + elapsed.get() },
            pause = { elapsed.addAndGet(it) })
        val result = client.scheduleWithUpdates(AiringSettings(), now) { batch ->
            if (firstBatchAt < 0 && batch.isNotEmpty()) firstBatchAt = elapsed.get()
        }
        assertEquals(1, gatewayCalls)
        assertEquals(5, directCalls)
        assertEquals(1300L, firstBatchAt)
        assertEquals(9700L, elapsed.get())
        assertEquals(5, result.size)
        // Same five-page fixture under the previous shared 2100ms gateway/direct
        // pacing: ten attempts, with no visible snapshot until the final response.
        val previousFirstVisible = 9 * 2100L + 550L
        println("Airing fixture: first visible ${previousFirstVisible}ms -> ${firstBatchAt}ms; full ${previousFirstVisible}ms -> ${elapsed.get()}ms; failed gateway calls 5 -> $gatewayCalls")
    }

    @Test fun `gateway cache hits have no upstream pacing delay`() = runBlocking {
        val elapsed = AtomicLong()
        var waits = 0L
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++; elapsed.addAndGet(50)
            val more = chain.request().url.queryParameter("page") == "1"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .header("X-Nuvio-Cache", "HIT")
                .body("""{"data":{"Page":{"pageInfo":{"hasNextPage":$more},"airingSchedules":[]}}}""".toResponseBody()).build()
        }.build()
        val client = AiringClient(http, "https://gateway.test", clock = { now.toEpochMilli() + elapsed.get() },
            pause = { waits += it; elapsed.addAndGet(it) })
        client.schedule(AiringSettings(), now)
        assertEquals(4, requests)
        assertEquals(0L, waits)
        assertEquals(200L, elapsed.get())
        println("Cached four-page fixture: 6350ms -> ${elapsed.get()}ms; pacing wait 0ms")
    }
}

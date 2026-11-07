package com.nuvio.app.features.myanimelist

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.test.*

class MalClientListEditingTest {
    private fun HttpExchange.reply(code: Int, body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) } else close()
    }

    private fun <T> withServer(setup: (HttpServer) -> Unit, block: suspend (MalClient) -> T): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        setup(server)
        server.start()
        return try {
            runBlocking { block(MalClient(api = "http://127.0.0.1:${server.address.port}/v2/")) }
        } finally { server.stop(0) }
    }

    @Test fun episodeProgressUsesMalsWriteFieldName() {
        val forms = mutableListOf<Map<String, String>>()
        val result = withServer({ server ->
            server.createContext("/v2/anime/30/my_list_status") { exchange ->
                assertEquals("PATCH", exchange.requestMethod)
                forms += exchange.requestBody.readBytes().toString(Charsets.UTF_8).split('&').associate {
                    val pair = it.split('=', limit = 2); pair[0] to URLDecoder.decode(pair[1], "UTF-8")
                }
                exchange.reply(200, """{"status":"completed","score":9,"num_episodes_watched":24,"is_rewatching":false}""")
            }
        }) { client -> client.update(30, "token", status = MalStatus.COMPLETED, watchedEpisodes = 24) }
        assertEquals(listOf(mapOf("status" to "completed", "num_watched_episodes" to "24")), forms)
        assertEquals(MalListStatus(MalStatus.COMPLETED, 9, 24), result)
        withServer({}) { client ->
            assertFailsWith<IllegalArgumentException> { client.update(30, "token", watchedEpisodes = -1) }
            assertFailsWith<IllegalArgumentException> { client.update(30, "token") }
        }
    }

    @Test fun removeAcceptsEmptyAndArrayBodiesAndTreatsMissingAsRemoved() {
        val methods = mutableListOf<String>()
        withServer({ server ->
            server.createContext("/v2/anime/1/my_list_status") { methods += it.requestMethod; it.reply(200, "[]") }
            server.createContext("/v2/anime/2/my_list_status") { methods += it.requestMethod; it.reply(200, "") }
            server.createContext("/v2/anime/3/my_list_status") { methods += it.requestMethod; it.reply(404, """{"error":"not_found"}""") }
            server.createContext("/v2/anime/4/my_list_status") { methods += it.requestMethod; it.reply(500, "") }
        }) { client ->
            client.remove(1, "token")
            client.remove(2, "token")
            client.remove(3, "token")
            assertEquals(500, assertFailsWith<MalHttpException> { client.remove(4, "token") }.status)
        }
        assertEquals(listOf("DELETE", "DELETE", "DELETE", "DELETE"), methods)
    }

    @Test fun statisticsAreReadFromTheSignedInUser() {
        val stats = withServer({ server ->
            server.createContext("/v2/users/@me") { exchange ->
                assertEquals("fields=anime_statistics", exchange.requestURI.rawQuery)
                exchange.reply(200, """{"id":1,"name":"tester","anime_statistics":{"num_items_watching":7,"num_items_completed":120,
                    "num_items_on_hold":3,"num_items_dropped":2,"num_items_plan_to_watch":40,"num_items":172,
                    "num_days":45.32,"num_episodes":2650,"mean_score":7.91}}""")
            }
        }) { client -> client.animeStatistics("token") }
        assertEquals(MalAnimeStatistics(7, 120, 3, 2, 40, 172, 2650, 45.32, 7.91), stats)
        assertEquals(172, stats.count(null))
        assertEquals(40, stats.count(MalStatus.PLAN_TO_WATCH))
    }

    @Test fun listRequestsSortAndDisplayFields() {
        val page = withServer({ server ->
            server.createContext("/v2/users/@me/animelist") { exchange ->
                val query = URLDecoder.decode(exchange.requestURI.rawQuery, "UTF-8")
                assertContains(query, "sort=list_score")
                assertContains(query, "alternative_titles")
                assertContains(query, "start_season")
                exchange.reply(200, """{"data":[
                    {"node":{"id":1,"title":"Kono Subarashii Sekai ni Shukufuku wo!","main_picture":{"medium":"m.jpg","large":"l.jpg"},
                      "num_episodes":10,"alternative_titles":{"en":"KonoSuba: God's Blessing on This Wonderful World!"},
                      "mean":8.11,"media_type":"tv","start_season":{"year":2016,"season":"winter"},"status":"finished_airing"},
                     "list_status":{"status":"completed","score":10,"num_episodes_watched":10}},
                    {"node":{"id":2,"title":"Same","alternative_titles":{"en":"same"},"media_type":"unknown","mean":0,
                      "status":"currently_airing"},"list_status":{"status":"watching","score":0,"num_episodes_watched":4}}
                  ],"paging":{}}""")
            }
        }) { client -> client.animeList("token", sort = MalListSort.SCORE) }
        val first = page.entries[0]
        assertEquals("l.jpg", first.imageUrl)
        assertEquals("KonoSuba: God's Blessing on This Wonderful World!", first.englishTitle)
        assertEquals(8.11, first.meanScore)
        assertEquals("tv", first.mediaType)
        assertEquals(2016, first.startYear)
        assertEquals("winter", first.startSeason)
        assertFalse(first.airing)
        val second = page.entries[1]
        assertNull(second.englishTitle, "an English title equal to the main title is not repeated")
        assertNull(second.mediaType)
        assertNull(second.meanScore)
        assertTrue(second.airing)
        assertNull(page.nextOffset)
    }
}

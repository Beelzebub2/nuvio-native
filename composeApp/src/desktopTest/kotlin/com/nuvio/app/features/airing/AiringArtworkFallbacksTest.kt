package com.nuvio.app.features.airing

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AiringArtworkFallbacksTest {
    private val now = Instant.parse("2026-10-21T12:00:00Z")
    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun array(value: String) = Json.parseToJsonElement(value).jsonArray
    private val media = AiringEvent(176301, 1, Instant.parse("2025-01-10T15:00:00Z").epochSecond, "Example", malId = 58514, isAdult = false)
    private val mapping = obj("""{"anilist_id":176301,"kitsu_id":48649,"themoviedb_id":{"tv":220542},"tvdb_id":431162,"season":{"tmdb":1,"tvdb":2},"episode_offset":{"tmdb":24}}""")
    private val identity = airingArtworkIdentity(media, mapping)!!
    private val video = MetaVideo("kitsu:48649:1", "Original", released = "2025-01-10", season = 1, episode = 1, overview = "Keep")
    private fun meta(videos: List<MetaVideo> = listOf(video)) = MetaDetails(id = "nativeairing:176301", type = "series", name = "Example", videos = videos)
    private val tmdb = obj("""{"season_number":1,"episodes":[{"show_id":220542,"season_number":1,"episode_number":25,"air_date":"2025-01-10","still_path":"/first.jpg"}]}""")
    private val show = obj("""{"id":67017,"externals":{"thetvdb":431162,"imdb":"tt26743760"}}""")
    private val maze = obj("""{"episodes":[{"season":2,"number":1,"airdate":"2025-01-10","image":{"original":"https://static.tvmaze.com/uploads/images/original_untouched/1/first.jpg"}}]}""")

    @Test fun `TMDB matches provider-specific offset and modifies only missing thumbnail`() = runBlocking {
        val paths = mutableListOf<String>()
        val client = AiringArtworkFallbacks({ paths += it; tmdb })
        val enriched = client.enrich(meta(), media, mapping)
        assertEquals(listOf("/artwork/tmdb-season?id=220542&season=1"), paths)
        assertEquals(video.copy(thumbnail = "https://image.tmdb.org/t/p/w500/first.jpg"), enriched.videos.single())
        assertEquals(meta().copy(videos = enriched.videos), enriched)
    }

    @Test fun `TVmaze follows TMDB absence verifies external ID and fills only remaining images`() = runBlocking {
        val paths = mutableListOf<String>()
        val client = AiringArtworkFallbacks({ path -> paths += path; when {
            path.contains("tmdb-season") -> null
            path.contains("lookup") -> show
            else -> maze
        } })
        val preserved = video.copy(id = "kitsu:48649:2", episode = 2, thumbnail = "https://anizip.test/preserved.jpg")
        val future = video.copy(id = "kitsu:48649:3", episode = 3, available = false)
        val result = client.enrich(meta(listOf(video, preserved, future)), media, mapping)
        assertTrue(result.videos.first().thumbnail!!.startsWith("https://static.tvmaze.com/"))
        assertEquals(listOf(preserved, future), result.videos.drop(1))
        assertEquals(listOf("/artwork/tmdb-season?id=220542&season=1", "/artwork/tvmaze-lookup?tvdb=431162", "/artwork/tvmaze-episodes?id=67017"), paths)
    }

    @Test fun `unknown mismatched and ambiguous identities cannot initiate lookups`() = runBlocking {
        var calls = 0
        val client = AiringArtworkFallbacks({ calls++; null })
        for (value in listOf(obj("{}"), obj("""{"anilist_id":999,"themoviedb_id":{"tv":220542},"season":{"tmdb":1}}"""),
            obj("""{"anilist_id":176301,"themoviedb_id":{"tv":220542},"tvdb_id":431162}"""))) {
            assertEquals(meta(), client.enrich(meta(), media, value))
        }
        assertEquals(0, calls)
    }

    @Test fun `wrong series season number missing date and duplicates keep placeholder`() {
        val valid = tmdb["episodes"].toString()
        for (value in listOf(
            valid.replace("220542", "999"), valid.replace("season_number\":1", "season_number\":2"),
            valid.replace("episode_number\":25", "episode_number\":1"), valid.replace("2025-01-10", "2025-01-11"),
            valid.replace("\"2025-01-10\"", "null"), valid.replace("[", "[${valid.removePrefix("[").removeSuffix("]")},"),
        )) assertEquals(video, fillArtwork(listOf(video), array(value), identity, true).single())
        assertEquals(video.copy(released = null), fillArtwork(listOf(video.copy(released = null)), tmdb["episodes"]!!.jsonArray, identity, true).single())
    }

    @Test fun `shorts use verified dates not inconsistent special numbers or main series images`() = runBlocking {
        val shortMedia = AiringEvent(218532, 3, now.minusSeconds(86400).epochSecond, "Maomao", malId = 65119, isAdult = false)
        val shortIdentity = airingArtworkIdentity(shortMedia, obj("{}"))!!
        val videos = (1..3).map { MetaVideo("mal:65119:$it", "Episode $it", season = 1, episode = it) }
        val specials = obj("""{"season_number":0,"episodes":[
            {"show_id":220542,"season_number":0,"episode_number":51,"air_date":"2026-10-05","still_path":"/one.jpg"},
            {"show_id":220542,"season_number":0,"episode_number":52,"air_date":"2026-10-12","still_path":"/two.jpg"},
            {"show_id":220542,"season_number":0,"episode_number":53,"air_date":"2026-10-19","still_path":"/three.jpg"}]}""")
        val client = AiringArtworkFallbacks({ path -> assertEquals("/artwork/tmdb-season?id=220542&season=0", path); specials })
        val result = client.enrich(meta(videos), shortMedia, obj("{}"))
        assertEquals(listOf("one", "two", "three"), result.videos.map { it.thumbnail!!.substringAfterLast('/').substringBefore('.') })
        assertEquals(videos, result.videos.map { it.copy(thumbnail = null) })
        val wrong = specials["episodes"]!!.jsonArray.toString().replace("season_number\":0", "season_number\":1")
        assertEquals(videos, fillArtwork(videos, array(wrong), shortIdentity, true))
        val conflict = videos.first().copy(released = "2026-10-12")
        assertEquals(conflict, fillArtwork(listOf(conflict), specials["episodes"]!!.jsonArray, shortIdentity, true).single())
        assertNull(airingArtworkIdentity(shortMedia.copy(malId = 58514), obj("{}")))
    }

    @Test fun `wrong TVmaze external identity stops before loading episode list`() = runBlocking {
        val paths = mutableListOf<String>()
        val client = AiringArtworkFallbacks({ path -> paths += path; if (path.contains("lookup")) obj("""{"id":999,"externals":{"thetvdb":999}}""") else null })
        assertEquals(meta(), client.enrich(meta(), media, mapping))
        assertEquals(2, paths.size)
    }

    @Test fun `season requests coalesce misses expire and cache is bounded`() = runBlocking {
        var time = now.toEpochMilli()
        var calls = 0
        val client = AiringArtworkFallbacks({ calls++; delay(5); null }, { time })
        val tmdbOnly = obj(mapping.toString().replace("\"tvdb_id\":431162,", ""))
        coroutineScope { List(8) { async { client.enrich(meta(), media, tmdbOnly) } }.awaitAll() }
        assertEquals(1, calls)
        time += 60_000L
        client.enrich(meta(), media, tmdbOnly)
        assertEquals(2, calls)
        for (id in 1..33) client.enrich(meta(), media, obj(tmdbOnly.toString().replace("220542", id.toString())))
        client.enrich(meta(), media, tmdbOnly)
        assertEquals(36, calls)
    }

    @Test fun `cancelled fallback request is not cached as missing`() = runBlocking {
        var calls = 0
        val client = AiringArtworkFallbacks({ calls++; if (calls == 1) throw CancellationException("fixture"); tmdb })
        try { client.enrich(meta(), media, mapping); fail("Must propagate cancellation") } catch (_: CancellationException) { }
        assertNotNull(client.enrich(meta(), media, mapping).videos.single().thumbnail)
        assertEquals(2, calls)
    }

    @Test fun `full native details HTTP flow keeps Fribb IDs and uses AniZip then TMDB then TVmaze`() = runBlocking {
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val r = chain.request()
            paths += r.url.encodedPath
            val (body, code) = when (r.url.encodedPath) {
                "/media" -> """{"data":{}}""" to 200
                "/episodes" -> "{}" to 404
                "/artwork/tmdb-season" -> "{}" to 503
                "/artwork/tvmaze-lookup" -> show.toString() to 200
                "/artwork/tvmaze-episodes" -> maze.toString() to 200
                else -> error("Unexpected HTTP request ${r.url.host}${r.url.encodedPath}")
            }
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody()).build()
        }.build()
        val client = AiringClient(http, gateway = "https://gateway.test", clock = { now.toEpochMilli() }, pause = {})
        client.javaClass.getDeclaredField("mappings").apply { isAccessible = true }.set(client, JsonArray(listOf(mapping)))
        client.javaClass.getDeclaredField("mappingSavedAt").apply { isAccessible = true }.setLong(client, System.currentTimeMillis())
        repeat(2) {
            val result = client.details(176301, listOf(media), AiringSettings())!!
            assertEquals("kitsu:48649:1", result.videos.single().id)
            assertEquals(1, result.videos.single().episode)
            assertNotNull(result.videos.single().thumbnail)
        }
        assertEquals(1, paths.count { it == "/episodes" })
        assertEquals(1, paths.count { it == "/artwork/tmdb-season" })
        assertEquals(1, paths.count { it == "/artwork/tvmaze-lookup" })
        assertEquals(1, paths.count { it == "/artwork/tvmaze-episodes" })
    }
}

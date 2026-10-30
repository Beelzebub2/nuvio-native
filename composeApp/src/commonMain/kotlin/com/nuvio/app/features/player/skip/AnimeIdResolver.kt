package com.nuvio.app.features.player.skip

import com.nuvio.app.features.addons.httpGetText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class AnimeSeasonIds(val mal: String?, val anilist: String?)

internal object AnimeIdResolver {
    private val cache = HashMap<String, AnimeSeasonIds?>()

    suspend fun resolveImdbSeason(imdbId: String, season: Int): AnimeSeasonIds? {
        if (!Regex("tt[0-9]+").matches(imdbId) || season <= 0) return null
        val key = "$imdbId:$season"
        if (cache.containsKey(key)) return cache[key]
        return try {
            val text = httpGetText("https://arm.haglund.dev/api/v2/imdb?id=$imdbId", timeoutMillis = 4_000L)
            parseAnimeSeasonIds(text, imdbId, season).also { cache[key] = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    fun clearCache() = cache.clear()
}

internal fun parseAnimeSeasonIds(text: String, imdbId: String, season: Int): AnimeSeasonIds? {
    val matches = Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }.filter {
        it["imdb"]?.jsonPrimitive?.content == imdbId &&
            it["thetvdb-season"]?.jsonPrimitive?.intOrNull == season &&
            it["media"]?.jsonPrimitive?.content == "TV"
    }
    // Multiple cours sharing a TV season need episode mappings we do not have.
    val entry = matches.singleOrNull() ?: return null
    fun positiveId(key: String): String? = entry[key]?.jsonPrimitive?.intOrNull
        ?.takeIf { it > 0 }?.toString()
    return AnimeSeasonIds(positiveId("myanimelist"), positiveId("anilist"))
        .takeIf { it.mal != null || it.anilist != null }
}

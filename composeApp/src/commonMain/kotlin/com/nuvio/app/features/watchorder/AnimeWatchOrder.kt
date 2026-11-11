package com.nuvio.app.features.watchorder

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.myanimelist.isLikelyMyAnimeListAnime
import com.nuvio.app.features.player.skip.AnimeIdResolver
import com.nuvio.app.features.player.skip.SimklIdResolver
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class AnimeWatchOrderStep(
    val malId: Int,
    val title: String,
    val kind: String,
    val required: Boolean,
    val releaseDate: String?,
)

internal data class AnimeWatchOrder(
    val franchiseName: String,
    val mode: String,
    val confidence: String,
    val steps: List<AnimeWatchOrderStep>,
) {
    val verified: Boolean get() = confidence == "verified"

    // Only call when Starlit considers the *current* title completed. We do
    // not assume other franchise entries were watched merely from their order.
    fun nextAfter(currentMalId: Int, includeOptional: Boolean): AnimeWatchOrderStep? {
        val index = steps.indexOfFirst { it.malId == currentMalId }
        if (index < 0) return null
        return steps.drop(index + 1).firstOrNull { it.required || includeOptional }
    }

    fun nextUnwatchedAfter(
        currentMalId: Int,
        includeOptional: Boolean,
        completedMalIds: Set<Int>,
        today: String,
    ): AnimeWatchOrderStep? {
        val index = steps.indexOfFirst { it.malId == currentMalId }
        if (index < 0) return null
        return steps.drop(index + 1).firstOrNull { step ->
            (step.required || includeOptional) &&
                step.malId !in completedMalIds &&
                // Unknown release dates are displayed in the list but are not
                // automatically recommended as already available.
                (step.releaseDate?.take(10)?.let { it <= today } == true)
        }
    }
}

internal object AnimeWatchOrderRepository {
    private const val GATEWAY = "https://starlit-api.rrmtools.uk/gateway/watch-order"
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = mutableMapOf<Pair<Int, String>, AnimeWatchOrder>()

    fun parse(text: String): AnimeWatchOrder? {
        return try {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return null
        val mode = root["mode"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it in setOf("recommended", "release", "chronological") } ?: return null
        val rawSteps = root["steps"] as? JsonArray ?: return null
        if (rawSteps.isEmpty() || rawSteps.size > 80) return null
        val steps = rawSteps.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val malId = (row["malId"] as? JsonPrimitive)?.takeUnless { it.isString }
                ?.intOrNull?.takeIf { it > 0 }
                ?: return@mapNotNull null
            val title = (row["title"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?.trim()?.takeIf { it.isNotEmpty() && it.length <= 180 } ?: return@mapNotNull null
            val kind = (row["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?.takeIf { it in setOf("tv", "movie", "ova", "special") } ?: return@mapNotNull null
            val required = (row["required"] as? JsonPrimitive)?.takeUnless { it.isString }
                ?.booleanOrNull ?: return@mapNotNull null
            AnimeWatchOrderStep(
                malId = malId,
                title = title,
                kind = kind,
                required = required,
                releaseDate = row["date"]?.jsonPrimitive?.contentOrNull,
            )
        }
        if (steps.size != rawSteps.size || steps.map { it.malId }.distinct().size != steps.size) return null
        AnimeWatchOrder(
            franchiseName = root["franchiseName"]?.jsonPrimitive?.contentOrNull
                ?.take(180).orEmpty(),
            mode = mode,
            confidence = root["confidence"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it in setOf("verified", "inferred", "partial") } ?: return null,
            steps = steps,
        )
        } catch (_: Exception) { null }
    }

    suspend fun load(malId: Int, mode: String, forceRefresh: Boolean = false): AnimeWatchOrder? {
        if (malId <= 0 || mode !in setOf("recommended", "release", "chronological")) return null
        if (!forceRefresh) cache[malId to mode]?.let { return it }
        return try {
            val text = httpGetText(GATEWAY + "?malId=" + malId + "&mode=" + mode, timeoutMillis = 14_000L)
            parse(text)?.takeIf { it.mode == mode && it.steps.any { s -> s.malId == malId } }
                ?.also { cache[malId to mode] = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            cache[malId to mode]
        }
    }

    suspend fun resolveMalId(meta: MetaDetails): Int? {
        if (!isLikelyMyAnimeListAnime(meta)) return null
        val direct = when (meta.id.substringBefore(':')) {
            "mal" -> meta.id.substringAfter(':').toIntOrNull()
            else -> null
        }
        if (direct != null && direct > 0) return direct
        for (link in meta.links) {
            val match = Regex("""myanimelist\.net/anime/(\d+)""", RegexOption.IGNORE_CASE)
                .find(link.url)
            val linkedId = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (linkedId != null && linkedId > 0) return linkedId
        }
        val prefix = meta.id.substringBefore(':')
        val animeId = meta.id.substringAfter(':').toIntOrNull()
        if (animeId != null && animeId > 0 && prefix in setOf("nativeairing", "anilist", "kitsu")) {
            val provider = if (prefix == "nativeairing") "anilist" else prefix
            SimklIdResolver.resolveIds(provider, animeId.toString(), "anime")
                ?.mal?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        val imdb = (meta.imdbId ?: meta.id).takeIf { it.matches(Regex("""tt\d{5,12}""")) }
            ?: return null
        return SimklIdResolver.resolveIds("imdb", imdb, "anime")
            ?.mal?.toIntOrNull()?.takeIf { it > 0 }
            ?: AnimeIdResolver.resolveImdbSeason(imdb, 1)?.mal?.toIntOrNull()?.takeIf { it > 0 }
    }
}

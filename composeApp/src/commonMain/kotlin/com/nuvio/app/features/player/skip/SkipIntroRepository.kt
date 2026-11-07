package com.nuvio.app.features.player.skip

import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object SkipIntroRepository {

    private val cache = HashMap<String, List<SkipInterval>>()
    private val animeSkipShowIdCache = HashMap<String, String>()
    private const val NO_ID = "__none__"

    private val introDbConfigured: Boolean
        get() = IntroDbConfig.URL.isNotBlank()

    suspend fun getMovieSkipIntervals(
        contentId: String?,
        videoId: String?,
        requireSkipIntroEnabled: Boolean = true,
        metadataImdbId: String? = null,
    ): List<SkipInterval> = coroutineScope {
        if (requireSkipIntroEnabled && !PlayerSettingsRepository.uiState.value.skipIntroEnabled) {
            return@coroutineScope emptyList()
        }
        val imdbId = resolveMovieSkipImdbId(
            contentId, videoId,
            resolveTmdb = { TmdbService.tmdbToImdb(it, "movie") },
            resolveAnime = { source, id -> SimklIdResolver.resolveIds(source, id)?.imdb },
            metadataImdbId = metadataImdbId,
        ) ?: return@coroutineScope emptyList()
        val cacheKey = "movie:$imdbId"
        cache[cacheKey]?.let { return@coroutineScope it }
        val introDb = async {
            if (introDbConfigured) SkipIntroApi.getIntroDbMovieSegments(imdbId)?.movieSkipIntervals().orEmpty()
            else emptyList()
        }
        val theIntroDb = async { fetchFromTheIntroDb(imdbId, null, null, isMovie = true) }
        mergeMovieSkipIntervals(introDb.await(), theIntroDb.await()).also {
            if (it.isNotEmpty()) cache[cacheKey] = it
        }
    }

    /**
     * One entry point for the built-in player and external players, so IMDb,
     * TMDB-backed, MAL and Kitsu media all share the same detection logic.
     */
    suspend fun getSkipIntervalsForContent(
        mediaType: String?,
        contentId: String?,
        videoId: String?,
        metadataImdbId: String?,
        season: Int?,
        episode: Int?,
        requireSkipIntroEnabled: Boolean = true,
    ): List<SkipInterval> {
        if (mediaType.equals("movie", ignoreCase = true)) {
            return getMovieSkipIntervals(contentId, videoId, requireSkipIntroEnabled, metadataImdbId)
        }
        if (requireSkipIntroEnabled && !PlayerSettingsRepository.uiState.value.skipIntroEnabled) return emptyList()
        val direct = resolveSkipIntervalLookup(videoId, season, episode)
        val imdbId = if (direct is SkipIntervalLookup.Imdb) direct.imdbId else resolveContentSkipImdbId(
            contentId, videoId, metadataImdbId,
            resolveTmdb = { TmdbService.tmdbToImdb(it, "series") },
            resolveAnime = { source, id -> SimklIdResolver.resolveIds(source, id)?.imdb },
        )
        return when (val lookup = direct ?: resolveSkipIntervalLookup(videoId, season, episode, imdbId)) {
            is SkipIntervalLookup.Imdb -> getSkipIntervals(
                lookup.imdbId, lookup.season, lookup.episode, requireSkipIntroEnabled,
            )
            is SkipIntervalLookup.Mal -> getSkipIntervalsForMal(
                lookup.malId, lookup.episode, requireSkipIntroEnabled, imdbId, season, episode,
            )
            is SkipIntervalLookup.Kitsu -> getSkipIntervalsForKitsu(
                lookup.kitsuId, lookup.episode, requireSkipIntroEnabled, imdbId, season, episode,
            )
            null -> emptyList()
        }
    }

    suspend fun getSkipIntervals(
        imdbId: String?,
        season: Int,
        episode: Int,
        requireSkipIntroEnabled: Boolean = true,
    ): List<SkipInterval> = coroutineScope {
        if (imdbId == null) return@coroutineScope emptyList()
        val settings = PlayerSettingsRepository.uiState.value
        if (requireSkipIntroEnabled && !settings.skipIntroEnabled) return@coroutineScope emptyList()

        val cacheKey = "$imdbId:$season:$episode"
        cache[cacheKey]?.let { return@coroutineScope it }

        val introDbDeferred = async {
            if (introDbConfigured) fetchFromIntroDb(imdbId, season, episode) else emptyList()
        }
        // Resolve IMDB -> season-specific MAL/AniList via Simkl full_anime_seasons
        val simklIdsDeferred = async { SimklIdResolver.resolveIdsForImdbEpisode(imdbId, season, episode) }
        val simklIds = simklIdsDeferred.await()
        // IMDb playback must also work in builds without Simkl credentials.
        val publicIds = if (simklIds?.mal == null && simklIds?.anilist == null) {
            AnimeIdResolver.resolveImdbSeason(imdbId, season)
        } else null
        val malId = simklIds?.mal ?: publicIds?.mal
        val anilistId = simklIds?.anilist ?: publicIds?.anilist

        // Remap the TVDB episode number to the anime-entry-local episode number.
        val animeEpisode = if (simklIds != null) {
            val mapping = SimklIdResolver.getEpisodeMapping(simklIds.simklId, simklIds.type)
            mapping.firstOrNull { it.tvdbSeason == season && it.tvdbEpisode == episode }
                ?.animeEpisode
                ?: episode
        } else episode

        val aniSkipDeferred = async {
            if (malId != null) fetchFromAniSkip(malId, animeEpisode) else emptyList()
        }
        val animeSkipDeferred = async {
            if (anilistId != null) fetchFromAnimeSkip(anilistId, animeEpisode, season = null) else emptyList()
        }

        val introDb = introDbDeferred.await()
        val animeSkip = animeSkipDeferred.await()
        val aniSkip = aniSkipDeferred.await()
        val existing = mergeByPriority(introDb, animeSkip, aniSkip)
        // Keep anime opening markers authoritative; supplement TV shows when
        // their episode has no opening in the existing providers.
        val fallback = if (existing.none { AutoSkipSegmentType.fromSkipIntervalType(it.type) == AutoSkipSegmentType.INTRO }) {
            fetchFromTheIntroDb(imdbId, season, episode, isMovie = false)
        } else emptyList()
        return@coroutineScope mergeByPriority(introDb + fallback, animeSkip, aniSkip).also {
            if (it.isNotEmpty()) cache[cacheKey] = it
        }
    }

    suspend fun getSkipIntervalsForMal(
        malId: String,
        episode: Int,
        requireSkipIntroEnabled: Boolean = true,
        imdbId: String? = null,
        imdbSeason: Int? = null,
        imdbEpisode: Int? = null,
    ): List<SkipInterval> = coroutineScope {
        val settings = PlayerSettingsRepository.uiState.value
        if (requireSkipIntroEnabled && !settings.skipIntroEnabled) return@coroutineScope emptyList()

        val cacheKey = "mal:$malId:$episode"
        cache[cacheKey]?.let { return@coroutineScope it }

        val aniSkipDeferred = async { fetchFromAniSkip(malId, episode) }

        val simklIdsDeferred = async { SimklIdResolver.resolveIds("mal", malId) }
        val simklIds = simklIdsDeferred.await()
        val resolvedImdbId = imdbId ?: simklIds?.imdb

        // Anime-Skip only needs the AniList ID. Missing TVDB episode mappings
        // must not suppress an otherwise valid opening or recap.
        val animeSkipDeferred = async {
            simklIds?.anilist?.let { fetchFromAnimeSkip(it, episode, season = null) } ?: emptyList()
        }
        val introDb = if (introDbConfigured && resolvedImdbId != null) {
            val tvdb = if (imdbSeason == null && simklIds != null) {
                SimklIdResolver.resolveEpisodeTvdb("mal", malId, episode)
            } else null
            val introDbSeason = imdbSeason ?: tvdb?.first
            if (introDbSeason != null) {
                fetchFromIntroDb(resolvedImdbId, introDbSeason, imdbEpisode ?: tvdb?.second ?: episode)
            } else emptyList()
        } else emptyList()

        return@coroutineScope mergeByPriority(introDb, animeSkipDeferred.await(), aniSkipDeferred.await()).also {
            if (it.isNotEmpty()) cache[cacheKey] = it
        }
    }

    suspend fun getSkipIntervalsForKitsu(
        kitsuId: String,
        episode: Int,
        requireSkipIntroEnabled: Boolean = true,
        imdbId: String? = null,
        imdbSeason: Int? = null,
        imdbEpisode: Int? = null,
    ): List<SkipInterval> = coroutineScope {
        val settings = PlayerSettingsRepository.uiState.value
        if (requireSkipIntroEnabled && !settings.skipIntroEnabled) return@coroutineScope emptyList()

        val cacheKey = "kitsu:$kitsuId:$episode"
        cache[cacheKey]?.let { return@coroutineScope it }

        val simklIdsDeferred = async { SimklIdResolver.resolveIds("kitsu", kitsuId) }
        val simklIds = simklIdsDeferred.await()
        val malIdStr = simklIds?.mal
        val resolvedImdbId = imdbId ?: simklIds?.imdb

        val aniSkipDeferred = async {
            if (malIdStr != null) fetchFromAniSkip(malIdStr, episode) else emptyList()
        }

        val animeSkipDeferred = async {
            simklIds?.anilist?.let { fetchFromAnimeSkip(it, episode, season = null) } ?: emptyList()
        }
        val introDb = if (introDbConfigured && resolvedImdbId != null) {
            val tvdb = if (imdbSeason == null && simklIds != null) {
                SimklIdResolver.resolveEpisodeTvdb("kitsu", kitsuId, episode)
            } else null
            val introDbSeason = imdbSeason ?: tvdb?.first
            if (introDbSeason != null) {
                fetchFromIntroDb(resolvedImdbId, introDbSeason, imdbEpisode ?: tvdb?.second ?: episode)
            } else emptyList()
        } else emptyList()

        return@coroutineScope mergeByPriority(introDb, animeSkipDeferred.await(), aniSkipDeferred.await()).also {
            if (it.isNotEmpty()) cache[cacheKey] = it
        }
    }

    internal fun mergeByPriority(
        introDb: List<SkipInterval>,
        animeSkip: List<SkipInterval>,
        aniSkip: List<SkipInterval>,
    ): List<SkipInterval> {
        val chosen = LinkedHashMap<String, SkipInterval>()
        for (result in listOf(introDb, animeSkip, aniSkip)) {
            for (interval in result) {
                if (!interval.hasValidSeekTimes()) continue
                val category = segmentCategory(interval.type) ?: continue
                if (category !in chosen) chosen[category] = interval
            }
        }
        // Anime-specific markers describe the opening, whereas generic TV data can
        // label the preceding cold open as the intro. Preserve other categories' priority.
        (animeSkip + aniSkip).firstOrNull {
            segmentCategory(it.type) == "opening" && it.hasValidSeekTimes()
        }?.let { chosen["opening"] = it }
        return chosen.values.toList()
    }

    internal fun mergeMovieSkipIntervals(
        introDb: List<SkipInterval>,
        theIntroDb: List<SkipInterval>,
    ): List<SkipInterval> {
        val selected = linkedMapOf<String, SkipInterval>()
        (introDb + theIntroDb).forEach { interval ->
            if (interval.hasValidSeekTimes() && interval.type !in selected) {
                selected[interval.type] = interval
            }
        }
        val credits = selected["movie-credits"]
        val postCredits = selected["post-credits"]
        if (credits != null && postCredits != null &&
            postCredits.startTime < credits.endTime && postCredits.endTime > credits.startTime
        ) {
            val clipped = credits.copy(endTime = postCredits.startTime)
            if (clipped.hasValidSeekTimes()) selected["movie-credits"] = clipped
            else selected.remove("movie-credits")
        }
        return selected.values.toList()
    }

    private suspend fun fetchFromTheIntroDb(
        imdbId: String,
        season: Int?,
        episode: Int?,
        isMovie: Boolean,
    ): List<SkipInterval> = SkipIntroApi.getTheIntroDbSegments(imdbId, season, episode)
        ?.skipIntervals(isMovie)
        .orEmpty()

    private fun segmentCategory(type: String): String? = when (type.lowercase()) {
        "intro", "op", "mixed-op" -> "opening"
        "outro", "ed", "mixed-ed", "credits", "ending" -> "ending"
        "recap" -> "recap"
        else -> null
    }

    private suspend fun fetchFromIntroDb(imdbId: String, season: Int, episode: Int): List<SkipInterval> {
        return try {
            val data = SkipIntroApi.getIntroDbSegments(imdbId, season, episode)
            if (data == null) return emptyList()
            listOfNotNull(
                data.intro.toSkipIntervalOrNull("intro"),
                data.recap.toSkipIntervalOrNull("recap"),
                data.outro.toSkipIntervalOrNull("outro"),
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun IntroDbSegment?.toSkipIntervalOrNull(type: String): SkipInterval? {
        if (this == null) return null
        val start = startSec ?: startMs?.let { it / 1000.0 }
        val end = endSec ?: endMs?.let { it / 1000.0 }
        if (start == null || end == null || end <= start) return null
        return SkipInterval(startTime = start, endTime = end, type = type, provider = "introdb")
    }

    private suspend fun fetchFromAniSkip(malId: String, episode: Int): List<SkipInterval> {
        return try {
            val response = SkipIntroApi.getAniSkipTimes(malId, episode)
            if (response == null) return emptyList()
            if (!response.found) return emptyList()
            response.results?.map { result ->
                SkipInterval(
                    startTime = result.interval.startTime,
                    endTime = result.interval.endTime,
                    type = result.skipType,
                    provider = "aniskip",
                )
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchFromAnimeSkip(anilistId: String, episode: Int, season: Int?): List<SkipInterval> {
        val settings = PlayerSettingsRepository.uiState.value
        val clientId = settings.animeSkipClientId.trim()
        if (clientId.isBlank()) return emptyList()
        if (!settings.animeSkipEnabled) return emptyList()

        return try {
            val showIds = resolveAnimeSkipShowIds(anilistId, clientId)
            if (showIds.isEmpty()) return emptyList()

            for (showId in showIds) {
                val query = "{ findEpisodesByShowId(showId: \"$showId\") { season number timestamps { at type { name } } } }"
                val response = SkipIntroApi.queryAnimeSkip(clientId, query) ?: continue
                val episodes = response.data?.findEpisodesByShowId ?: continue

                // Community entries for the same episode may be incomplete.
                // Choose the most comprehensive *safe* set of intervals.
                val result = episodes.filter { ep ->
                    ep.number?.toIntOrNull() == episode &&
                        (season == null || ep.season?.toIntOrNull() == season)
                }.bestSkipIntervals()
                if (result.isNotEmpty()) return result
            }
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun resolveAnimeSkipShowIds(anilistId: String, clientId: String): List<String> {
        animeSkipShowIdCache[anilistId]?.let { cached ->
            return if (cached == NO_ID) emptyList() else listOf(cached)
        }
        val query = "{ findShowsByExternalId(service: ANILIST, serviceId: \"$anilistId\") { id } }"
        val showIds = try {
            SkipIntroApi.queryAnimeSkip(clientId, query)
                ?.data?.findShowsByExternalId?.map { it.id } ?: emptyList()
        } catch (_: Exception) { emptyList() }

        if (showIds.size == 1) animeSkipShowIdCache[anilistId] = showIds[0]
        else if (showIds.isEmpty()) animeSkipShowIdCache[anilistId] = NO_ID
        return showIds
    }

    suspend fun submitIntro(
        imdbId: String,
        season: Int,
        episode: Int,
        startSec: Double,
        endSec: Double,
        segmentType: String,
    ): Boolean {
        val settings = PlayerSettingsRepository.uiState.value
        val apiKey = settings.introDbApiKey.trim()
        if (!settings.introSubmitEnabled || apiKey.isBlank()) return false

        val request = SubmitIntroRequest(
            imdbId = imdbId,
            season = season,
            episode = episode,
            startSec = startSec,
            endSec = endSec,
            startMs = (startSec * 1000).toLong(),
            endMs = (endSec * 1000).toLong(),
            segmentType = segmentType,
        )

        return SkipIntroApi.submitIntro(apiKey, request)
    }

    suspend fun verifyIntroDbApiKey(apiKey: String): Boolean {
        return SkipIntroApi.verifyIntroDbApiKey(apiKey)
    }

    fun clearCache() {
        cache.clear()
        animeSkipShowIdCache.clear()
        SimklIdResolver.clearCache()
        AnimeIdResolver.clearCache()
    }
}

internal suspend fun resolveMovieSkipImdbId(
    contentId: String?,
    videoId: String?,
    resolveTmdb: suspend (Int) -> String?,
    resolveAnime: suspend (String, String) -> String?,
    metadataImdbId: String? = null,
): String? = resolveContentSkipImdbId(contentId, videoId, metadataImdbId, resolveTmdb, resolveAnime)

internal suspend fun resolveContentSkipImdbId(
    contentId: String?,
    videoId: String?,
    metadataImdbId: String?,
    resolveTmdb: suspend (Int) -> String?,
    resolveAnime: suspend (String, String) -> String?,
): String? {
    val ids = listOfNotNull(contentId, metadataImdbId, videoId).map(String::trim).distinct()
    val imdbPattern = Regex("tt[0-9]+")
    ids.map { it.substringBefore(':') }.firstOrNull { imdbPattern.matches(it) }?.let { return it }
    return ids.firstNotNullOfOrNull { id ->
        val parts = id.split(':')
        val value = parts.getOrNull(1)?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: return@firstNotNullOfOrNull null
        when (parts.first().lowercase()) {
            "tmdb", "movie", "series", "tv" ->
                value.toIntOrNull()?.takeIf { it > 0 }?.let { resolveTmdb(it) }
            "mal", "kitsu" -> resolveAnime(parts.first().lowercase(), value)
            else -> null
        }?.trim()?.takeIf { imdbPattern.matches(it) }
    }
}

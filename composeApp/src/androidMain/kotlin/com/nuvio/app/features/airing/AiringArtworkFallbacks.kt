package com.nuvio.app.features.airing

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.time.LocalDate

/** Artwork-only provider identities. Never used to construct stream or download IDs. */
internal data class AiringArtworkIdentity(
    val tmdb: Int? = null,
    val tmdbSeason: Int? = null,
    val tmdbOffset: Int = 0,
    val tvdb: Int? = null,
    val imdb: String? = null,
    val tvdbSeason: Int? = null,
    val tvdbOffset: Int = 0,
    val datedSpecials: Boolean = false,
)

internal fun airingArtworkIdentity(media: AiringEvent, mapping: JsonObject): AiringArtworkIdentity? {
    // Verified separately from Fribb: Maomao shorts S3 are TMDB specials of the
    // parent show. Match dates, never TVDB's different special numbers (+1).
    // https://www.themoviedb.org/tv/220542/season/0 (2026-10-05/12/19).
    if (media.mediaId == 218532 && (media.malId == null || media.malId == 65119)) {
        return AiringArtworkIdentity(tmdb = 220542, tmdbSeason = 0, datedSpecials = true)
    }
    if (mapping.number("anilist_id") != media.mediaId) return null
    val tmdb = mapping.obj("themoviedb_id").number("tv")?.takeIf { it > 0 }
    val tvdb = mapping.number("tvdb_id")?.takeIf { it > 0 }
    val imdb = (mapping["imdb_id"] as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonPrimitive)?.contentOrNull?.takeIf { id -> Regex("tt\\d{5,12}").matches(id) }
    }.distinct().singleOrNull()
    val seasons = mapping.obj("season")
    val offsets = mapping.obj("episode_offset")
    // Unknown/ambiguous season mappings are deliberately left without artwork.
    return AiringArtworkIdentity(tmdb, seasons.number("tmdb")?.takeIf { it in 0..1000 },
        offsets.number("tmdb") ?: 0, tvdb, imdb,
        seasons.number("tvdb")?.takeIf { it in 0..1000 }, offsets.number("tvdb") ?: 0)
}

internal class AiringArtworkFallbacks(
    private val fetch: suspend (String) -> JsonObject?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val cache = linkedMapOf<String, Pair<Long, JsonObject?>>()

    private suspend fun metadata(path: String): JsonObject? = mutex.withLock {
        cache[path]?.takeIf { it.first > clock() }?.let { return@withLock it.second }
        val value = try { fetch(path) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        cache[path] = (clock() + if (value == null) 60_000L else 3600_000L) to value
        while (cache.size > 32) cache.remove(cache.keys.first())
        value
    }

    suspend fun enrich(meta: MetaDetails, media: AiringEvent, mapping: JsonObject): MetaDetails {
        if (meta.videos.none(::needsArtwork)) return meta
        val identity = airingArtworkIdentity(media, mapping) ?: return meta
        if (!identity.datedSpecials && meta.videos.none { needsArtwork(it) && episodeDate(it.released) != null }) return meta
        var videos = meta.videos
        if (identity.tmdb != null && identity.tmdbSeason != null) {
            val season = metadata("/artwork/tmdb-season?id=${identity.tmdb}&season=${identity.tmdbSeason}")
            // TMDB season's id is the season resource ID, not the series ID.
            if (season?.number("season_number") == identity.tmdbSeason) {
                videos = fillArtwork(videos, season["episodes"] as? JsonArray, identity, tmdb = true)
            }
        }
        if (videos.any(::needsArtwork) && identity.tvdbSeason != null && (identity.tvdb != null || identity.imdb != null)) {
            val lookup = if (identity.tvdb != null) "tvdb=${identity.tvdb}" else "imdb=${identity.imdb}"
            val show = metadata("/artwork/tvmaze-lookup?$lookup")
            val externals = show?.obj("externals")
            val verified = if (identity.tvdb != null) externals?.number("thetvdb") == identity.tvdb
                else externals?.text("imdb") == identity.imdb
            val id = show?.number("id")?.takeIf { it > 0 }
            if (verified && id != null) {
                val episodes = metadata("/artwork/tvmaze-episodes?id=$id")
                videos = fillArtwork(videos, episodes?.get("episodes") as? JsonArray, identity, tmdb = false)
            }
        }
        return meta.copy(videos = videos)
    }
}

private fun needsArtwork(video: MetaVideo): Boolean = video.available && video.thumbnail.isNullOrBlank()

private fun episodeDate(value: String?): LocalDate? = value?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

internal fun fillArtwork(
    videos: List<MetaVideo>, episodes: JsonArray?, identity: AiringArtworkIdentity, tmdb: Boolean,
): List<MetaVideo> {
    val season = if (tmdb) identity.tmdbSeason else identity.tvdbSeason
    val offset = if (tmdb) identity.tmdbOffset else identity.tvdbOffset
    val records = episodes.orEmpty().mapNotNull { it as? JsonObject }
    return videos.map { video ->
        if (!needsArtwork(video)) return@map video
        // These first three shorts dates were independently verified at the
        // official special list. They also cover AniList/AniZip outages, without
        // inventing a weekly cadence or changing the source's released field.
        val verifiedShortDate = if (identity.datedSpecials) when (video.episode) {
            1 -> LocalDate.parse("2026-10-05")
            2 -> LocalDate.parse("2026-10-12")
            3 -> LocalDate.parse("2026-10-19")
            else -> null
        } else null
        val sourceDate = episodeDate(video.released)
        if (sourceDate != null && verifiedShortDate != null && sourceDate != verifiedShortDate) return@map video
        val date = sourceDate ?: verifiedShortDate ?: return@map video
        if (identity.datedSpecials && (date < LocalDate.parse("2026-10-05") || date >= LocalDate.parse("2027-01-01"))) return@map video
        val matches = records.filter { entry ->
            val entrySeason = entry.number(if (tmdb) "season_number" else "season")
            val number = entry.number(if (tmdb) "episode_number" else "number")
            entrySeason == season && (!tmdb || entry.number("show_id") == identity.tmdb) &&
                episodeDate(entry.text(if (tmdb) "air_date" else "airdate")) == date &&
                (if (identity.datedSpecials) tmdb && season == 0 else number != null && number == video.episode?.plus(offset))
        }
        // Duplicate dates/records must never pick an arbitrary scene.
        val match = matches.singleOrNull() ?: return@map video
        val image = if (tmdb) match.text("still_path")?.takeIf { Regex("/[A-Za-z0-9_-]+\\.(jpg|png|webp)").matches(it) }
            ?.let { "https://image.tmdb.org/t/p/w500$it" }
        else match.obj("image").text("original")?.takeIf {
            it.startsWith("https://static.tvmaze.com/uploads/images/")
        }
        if (image == null) video else video.copy(thumbnail = image)
    }
}

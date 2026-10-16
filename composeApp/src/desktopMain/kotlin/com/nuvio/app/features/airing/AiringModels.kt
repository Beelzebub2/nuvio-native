package com.nuvio.app.features.airing

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId

@Serializable
internal data class AiringSettings(
    val zone: String = "Europe/Lisbon",
    val airType: String = "broadcast",
    val historyDays: Int = 2,
    val includeEstimated: Boolean = true,
    val hideNsfw: Boolean = true,
)

@Serializable
internal data class AiringEvent(
    val mediaId: Int,
    val episode: Int,
    val airingAt: Long,
    val name: String,
    val poster: String? = null,
    val banner: String? = null,
    val malId: Int? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val totalEpisodes: Int? = null,
    val airType: String = "broadcast",
    val estimated: Boolean = false,
    val isBreak: Boolean = false,
    // Null means the source has not verified the title's adult classification.
    val isAdult: Boolean? = null,
) {
    fun preview() = MetaPreview(
        id = "$NATIVE_AIRING_PREFIX$mediaId", type = "series", name = name,
        poster = poster, rawPosterUrl = poster, banner = banner,
        description = description, genres = genres,
        watchLaterEpisode = com.nuvio.app.features.watchlater.WatchLaterEpisode("$NATIVE_AIRING_PREFIX$mediaId:$episode", "Episode $episode", 1, episode, banner),
    )
}

@Serializable
internal data class AiringSnapshot(
    val events: List<AiringEvent> = emptyList(),
    val savedAt: Long = 0,
    val airType: String = "broadcast",
    // Legacy snapshots are unclassified, so they cannot be reused under either policy.
    val hideNsfw: Boolean? = null,
)

internal enum class AiringWindow(val label: String) {
    Now("Airing Now")
}

/** Apply the same policy to live responses, saved snapshots, full catalogs and dated schedules. */
internal fun AiringEvent.allowedBy(settings: AiringSettings): Boolean =
    !settings.hideNsfw || (isAdult == false && genres.none { it.equals("Hentai", ignoreCase = true) })

internal fun filterAiringContent(events: List<AiringEvent>, settings: AiringSettings): List<AiringEvent> {
    if (!settings.hideNsfw) return events
    // A title cannot be shown as safe when different episodes disagree about its
    // classification. Evaluate the whole batch before dropping individual records.
    val blockedTitles = events.asSequence().filter { event ->
        event.isAdult == true || event.genres.any { it.equals("Hentai", ignoreCase = true) }
    }.map { it.mediaId }.toSet()
    return events.filter { it.mediaId !in blockedTitles && it.allowedBy(settings) }
}

internal data class TsuzukiScheduleRequest(val gatewayPath: String, val directUrl: String)

internal fun tsuzukiScheduleRequest(
    start: java.time.LocalDate, days: Int, settings: AiringSettings,
): TsuzukiScheduleRequest {
    val query = "start=$start&days=$days&airType=${settings.airType}"
    return TsuzukiScheduleRequest(
        gatewayPath = if (settings.hideNsfw) "/release-schedule?$query" else "/release-schedule-adult?$query",
        directUrl = "https://tsuzuki.top/api/v1/schedule?$query" + if (settings.hideNsfw) "" else "&includeAdult=1",
    )
}

internal fun selectAiringEvents(
    events: List<AiringEvent>, settings: AiringSettings, now: Instant, window: AiringWindow,
): List<AiringEvent> {
    val zone = ZoneId.of(settings.zone)
    val today = now.atZone(zone).toLocalDate()
    val start = today.minusDays((settings.historyDays - 1).toLong())
    val eligible = filterAiringContent(events, settings).filter {
        !it.isBreak && it.airingAt > 0 && it.airType == settings.airType &&
            (settings.includeEstimated || !it.estimated)
    }
    fun inWindow(event: AiringEvent): Boolean {
        val date = Instant.ofEpochSecond(event.airingAt).atZone(zone).toLocalDate()
        return date >= start && date <= today && event.airingAt <= now.epochSecond
    }
    return eligible.filter(::inWindow).sortedByDescending { it.airingAt }.distinctBy { it.mediaId }
}

internal fun selectScheduledEvents(events: List<AiringEvent>, settings: AiringSettings, date: java.time.LocalDate): List<AiringEvent> =
    filterAiringContent(events, settings).filter { !it.isBreak && it.airingAt > 0 && it.airType == settings.airType &&
        (settings.includeEstimated || !it.estimated) && Instant.ofEpochSecond(it.airingAt).atZone(ZoneId.of(settings.zone)).toLocalDate() == date }
        .sortedBy { it.airingAt }.distinctBy { "${it.mediaId}:${it.episode}" }

internal fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
internal fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())
internal fun titleText(element: JsonElement?): String? = when (element) {
    is JsonPrimitive -> element.contentOrNull
    is JsonObject -> listOf("english", "romaji", "en", "native", "ja").firstNotNullOfOrNull { element.text(it) }
    else -> null
}
internal fun coverText(element: JsonElement?): String? = when (element) {
    is JsonPrimitive -> element.contentOrNull
    is JsonObject -> listOf("extraLarge", "large", "medium").firstNotNullOfOrNull { element.text(it) }
    else -> null
}?.takeIf { it.startsWith("https://") }

internal fun parseAniListSchedule(page: JsonObject): List<AiringEvent> =
    (page["airingSchedules"] as? JsonArray).orEmpty().mapNotNull { element ->
        val record = element as? JsonObject ?: return@mapNotNull null
        eventFromAniList(record.obj("media"), record.number("episode"), record.number("airingAt")?.toLong())
    }

/** Skip invalid provider records; demand adult classification for every displayable item. */
internal fun hasClassifiedAniListMedia(page: JsonObject): Boolean =
    page["airingSchedules"] is JsonArray && parseAniListSchedule(page).all { it.isAdult != null }

internal fun eventFromAniList(media: JsonObject, episode: Int?, airingAt: Long?): AiringEvent? {
    val id = media.number("id")?.takeIf { it > 0 } ?: return null
    if (episode == null || episode !in 1..10000 || airingAt == null || airingAt <= 0) return null
    return AiringEvent(
        mediaId = id, episode = episode, airingAt = airingAt,
        name = titleText(media["title"]) ?: "Anime $id", poster = coverText(media["coverImage"]),
        banner = media.text("bannerImage"), malId = media.number("idMal"),
        description = media.text("description")?.replace(Regex("<[^>]+>"), " ")?.trim(),
        genres = (media["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        totalEpisodes = media.number("episodes"),
        isAdult = (media["isAdult"] as? JsonPrimitive)?.booleanOrNull,
    )
}

internal fun parseTsuzukiSchedule(payload: JsonObject, airType: String, sourceExcludesAdult: Boolean = false): List<AiringEvent> {
    val records = payload["episodes"] as? JsonArray ?: error("Schedule provider returned no episode list")
    return records.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val id = item.number("mediaId")?.takeIf { it > 0 } ?: return@mapNotNull null
        val episode = item.number("episode")?.takeIf { it in 1..10000 } ?: return@mapNotNull null
        val at = (item["airingAt"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
        if (at <= 0 || item.text("airType") != airType) return@mapNotNull null
        AiringEvent(
            mediaId = id, episode = episode, airingAt = at,
            name = titleText(item["title"]) ?: "Anime $id", poster = coverText(item["coverImage"]),
            airType = airType, estimated = item.flag("estimated") || !item.flag("exact"),
            isBreak = item.flag("isBreak") || item.obj("note").text("kind") == "break",
            isAdult = (item["isAdult"] as? JsonPrimitive)?.booleanOrNull
                ?: (item.obj("media")["isAdult"] as? JsonPrimitive)?.booleanOrNull
                ?: if (sourceExcludesAdult) false else null,
        )
    }
}

/** Keep anime-specific episode numbering; TVDB season numbering must not change stream IDs. */
internal fun buildAiringDetails(
    media: AiringEvent, events: List<AiringEvent>, mappings: JsonObject, episodes: JsonObject,
    now: Instant,
): MetaDetails {
    val base = mappings.number("kitsu_id")?.takeIf { it > 0 }?.let { "kitsu:$it" }
        ?: (mappings.number("mal_id") ?: media.malId)?.takeIf { it > 0 }?.let { "mal:$it" }
        ?: "anilist:${media.mediaId}"
    val scheduled = events.filter { it.mediaId == media.mediaId && it.airType == media.airType }
        .associateBy { it.episode }
    val numbers = (episodes.keys.mapNotNull { it.toIntOrNull() } + scheduled.keys).filter { it in 1..10000 }.toMutableSet()
    val lastAired = scheduled.values.filter { !it.isBreak && it.airingAt <= now.epochSecond }.maxOfOrNull { it.episode } ?: 0
    numbers.addAll(1..lastAired)
    val videos = numbers.sorted().map { number ->
        val detail = episodes[number.toString()] as? JsonObject ?: JsonObject(emptyMap())
        val event = scheduled[number]
        val released = event?.airingAt?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).toString() }
            ?: detail.text("airdate")
        val databaseReleased = released?.let { runCatching { java.time.LocalDate.parse(it.take(10)) <= now.atZone(ZoneId.of("UTC")).toLocalDate() }.getOrDefault(false) } ?: false
        MetaVideo(
            id = "$base:$number", season = 1, episode = number,
            title = titleText(detail["title"]) ?: "Episode $number", released = released,
            available = if (event != null) !event.isBreak && event.airingAt <= now.epochSecond else number <= lastAired || databaseReleased,
            thumbnail = detail.text("image"), overview = detail.text("overview"),
        )
    }
    return MetaDetails(
        id = "$NATIVE_AIRING_PREFIX${media.mediaId}", type = "series", name = media.name,
        poster = media.poster, background = media.banner, description = media.description,
        genres = media.genres, hasScheduledVideos = true, videos = videos,
        defaultVideoId = videos.lastOrNull { it.available }?.id,
    )
}

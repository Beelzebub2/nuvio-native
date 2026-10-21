package com.nuvio.app.features.airing

import com.nuvio.app.core.network.DesktopIPv4FirstDns
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.details.MetaDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AiringRateLimitException(val retryAt: Long) : IOException("Schedule provider is rate limited; will retry after the cooldown")
internal class AiringHttpException(val status: Int, host: String) : IOException("$host: HTTP $status")

internal class AiringClient(
    private val http: OkHttpClient = OkHttpClient.Builder().dns(DesktopIPv4FirstDns())
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build(),
    private val gateway: String = (System.getenv("NUVIO_AIRING_GATEWAY")
        ?: "https://nuvio-native-gateway.cloudflaredomainrrm.workers.dev").trimEnd('/'),
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mappingMutex = Mutex()
    private var mappings: JsonArray? = null
    private var mappingSavedAt = 0L
    private val requestMutexes = ConcurrentHashMap<String, Mutex>()
    private val staleGatewayUntil = ConcurrentHashMap<String, Long>()
    private val cooldowns = ConcurrentHashMap<String, Long>()
    private var nextDirectAniListAt = 0L
    private var nextGatewayAniListAt = 0L
    private var gatewayCacheHit = false
    private val gatewayUnavailableUntil = ConcurrentHashMap<String, Long>()
    private val classifications = mutableMapOf<Int, Pair<Long, AiringEvent>>()
    private val episodesMutex = Mutex()
    private val episodeMetadataCache = linkedMapOf<Int, Pair<Long, JsonObject?>>()
    private val artworkFallbacks = AiringArtworkFallbacks({ gatewayGet(it) }, clock)

    suspend fun schedule(settings: AiringSettings, now: Instant): List<AiringEvent> =
        scheduleWithUpdates(settings, now) {}

    suspend fun scheduleWithUpdates(
        settings: AiringSettings, now: Instant, onBatch: suspend (List<AiringEvent>) -> Unit,
    ): List<AiringEvent> = withContext(Dispatchers.Default) {
        // UTC windows share cache entries across time zones and all history controls.
        // Include the boundary days needed for local dates as far as UTC +/-14.
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        val first = today.minusDays(MAX_AIRING_HISTORY_DAYS.toLong())
        if (settings.airType != "broadcast") {
            suspend fun releaseSchedule(start: java.time.LocalDate, days: Int): List<AiringEvent> {
                val request = tsuzukiScheduleRequest(start, days, settings)
                val response = gatewayGet(request.gatewayPath) ?: get(request.directUrl)
                return parseTsuzukiSchedule(response, settings.airType)
            }
            val events = mutableListOf<AiringEvent>()
            suspend fun append(releases: List<AiringEvent>) {
                for (batch in releases.sortedByDescending { it.airingAt }.chunked(50)) {
                    events += if (settings.hideNsfw) classifyReleases(batch) else batch
                    onBatch(events.toList())
                }
            }
            append(releaseSchedule(first, 6))
            append(releaseSchedule(today.plusDays(1), 7))
            return@withContext events
        }
        val result = mutableListOf<AiringEvent>()
        // Keep each request inside the gateway's existing start/range limits.
        for ((from, until) in listOf(first to today.plusDays(1), today to today.plusDays(8))) {
            val recent = from == first
            val path = if (recent) "/schedule-recent" else "/schedule"
            val start = from.atStartOfDay(ZoneOffset.UTC).toEpochSecond() - 1
            val end = until.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            var complete = false
            for (number in 1..20) {
                val cached = gatewayGet("$path?start=$start&end=$end&page=$number${if (recent) "" else "&future=1"}")?.obj("data")
                // Older gateway deployments omit isAdult. Never treat unknown as safe:
                // request the original metadata from AniList before accepting a page.
                val response = cached?.takeIf { data ->
                    !settings.hideNsfw || hasClassifiedAniListMedia(data.obj("Page"))
                }
                    ?: graphql(if (recent) RECENT_QUERY else FUTURE_QUERY, buildJsonObject {
                        put("start", start); put("end", end); put("page", number)
                    })
                val page = response.obj("Page")
                if (page["airingSchedules"] !is JsonArray) error("AniList returned no schedule")
                result.addAll(parseAniListSchedule(page))
                onBatch(result.toList())
                if (!page.obj("pageInfo").flag("hasNextPage")) { complete = true; break }
            }
            check(complete) { "Schedule is too large to load completely; choose fewer history days" }
        }
        result
    }

    private suspend fun classifyReleases(events: List<AiringEvent>): List<AiringEvent> {
        val missing = events.map { it.mediaId }.distinct().sorted().filter {
            classifications[it]?.first?.let { saved -> clock() - saved < 86400_000L } != true
        }
        for (ids in missing.chunked(50)) {
            val data = gatewayGet("/classifications?ids=${ids.joinToString(",")}")?.obj("data")
                ?: graphql(CLASSIFICATION_QUERY, buildJsonObject { put("ids", JsonArray(ids.map(::JsonPrimitive))) })
            for (element in (data.obj("Page")["media"] as? JsonArray).orEmpty()) {
                val media = element as? JsonObject ?: continue
                val classified = eventFromAniList(media, 1, 1) ?: continue
                if (classified.isAdult != null) classifications[classified.mediaId] = clock() to classified
            }
        }
        return events.map { event ->
            val metadata = classifications[event.mediaId]?.second
            event.copy(isAdult = if (event.isAdult == true) true else metadata?.isAdult,
                genres = metadata?.genres.orEmpty(), hasAdultTags = metadata?.hasAdultTags == true)
        }
    }

    suspend fun details(id: Int, known: List<AiringEvent>, settings: AiringSettings): MetaDetails? = coroutineScope {
        val existing = known.lastOrNull { it.mediaId == id && it.airType == settings.airType }
        val mediaRequest = async { optional {
            val cached = gatewayGet("/media?id=$id")?.obj("data")
            cached?.takeIf {
                !settings.hideNsfw || eventFromAniList(it.obj("Media"), 1, 1)?.isAdult != null
            }
                ?: graphql(MEDIA_QUERY, buildJsonObject { put("id", id) })
        } }
        val mappingRequest = async { optional { mapping(id) } }
        val extraRequest = async { episodeMetadata(id) }
        val metadata = mediaRequest.await()
        val info = metadata?.obj("Media")
        val extra = extraRequest.await() ?: JsonObject(emptyMap())
        val databaseMapping = mappingRequest.await() ?: JsonObject(emptyMap())
        val record = info?.let { eventFromAniList(it, existing?.episode ?: 1, existing?.airingAt ?: 1) }
            ?.copy(airType = settings.airType) ?: existing ?: return@coroutineScope null
        if (!record.allowedBy(settings)) return@coroutineScope null
        if (filterAiringContent(known.filter { it.mediaId == id } + record, settings).isEmpty())
            return@coroutineScope null
        val historical = (metadata?.obj("Page")?.get("airingSchedules") as? JsonArray).orEmpty().mapNotNull { element ->
            val node = element as? JsonObject ?: return@mapNotNull null
            record.copy(episode = node.number("episode") ?: return@mapNotNull null,
                airingAt = node.number("airingAt")?.toLong() ?: return@mapNotNull null)
        }.filter { it.episode in 1..10000 && it.airingAt > 0 }
        // AniList broadcast history must never claim a sub or dub episode is available.
        val events = if (settings.airType == "broadcast") historical + known else known
        // Artwork providers may describe a different TVDB season/numbering scheme.
        // Keep stream/download identities on the existing anime mapping authority.
        artworkFallbacks.enrich(
            buildAiringDetails(record, events, databaseMapping, extra.obj("episodes"), Instant.ofEpochMilli(clock())),
            record, databaseMapping,
        )
    }

    internal suspend fun episodeMetadata(id: Int): JsonObject? = episodesMutex.withLock {
        episodeMetadataCache[id]?.takeIf { it.first > clock() }?.let { return@withLock it.second }
        fun matching(value: JsonObject?): JsonObject? = value?.takeIf {
            it.obj("mappings").number("anilist_id") == id && it["episodes"] is JsonObject
        }
        val value = try {
            matching(gatewayGet("/episodes?id=$id"))
                ?: matching(get("https://api.ani.zip/mappings?anilist_id=$id"))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        // Coalesce repeat detail opens, including unmapped anime, without per-card requests.
        episodeMetadataCache[id] = (clock() + if (value == null) 60_000L else 3600_000L) to value
        while (episodeMetadataCache.size > 32) episodeMetadataCache.remove(episodeMetadataCache.keys.first())
        value
    }

    internal suspend fun resolveMalId(id: String): Int? {
        val prefix = id.substringBefore(':')
        val number = id.substringAfter(':', "").substringBefore(':').toIntOrNull()?.takeIf { it > 0 } ?: return null
        if (prefix == "mal") return number
        val field = when (prefix) { "nativeairing", "anilist" -> "anilist_id"; "kitsu" -> "kitsu_id"; else -> return null }
        if (prefix == "nativeairing") NativeAiring.repository.state.value.snapshot.events.firstOrNull { it.mediaId == number }?.malId?.let { return it }
        return mapping(number, field)?.number("mal_id")
    }

    private suspend fun mapping(id: Int, field: String = "anilist_id"): JsonObject? = mappingMutex.withLock {
        val cache = DesktopStorage.cacheDir.resolve("airing-id-mappings.json")
        val now = System.currentTimeMillis()
        if (mappings == null && Files.isRegularFile(cache)) {
            withContext(Dispatchers.IO) {
                runCatching {
                    mappings = json.parseToJsonElement(Files.readString(cache)) as? JsonArray
                    mappingSavedAt = Files.getLastModifiedTime(cache).toMillis()
                }
            }
        }
        if (mappings == null || now - mappingSavedAt > 7 * 86400_000L) {
            val fresh = optional {
                json.parseToJsonElement(request(Request.Builder()
                    .url("https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-mini.json").build())) as? JsonArray
            }
            if (fresh != null) {
                mappings = fresh
                mappingSavedAt = now
                withContext(Dispatchers.IO) { runCatching { Files.writeString(cache, fresh.toString()) } }
            }
        }
        mappings.orEmpty().firstOrNull { (it as? JsonObject)?.number(field) == id } as? JsonObject
    }

    private suspend fun get(url: String): JsonObject = json.parseToJsonElement(
        request(Request.Builder().url(url).build())
    ).jsonObject

    private fun providerFor(request: Request): String = when {
        request.url.host == "graphql.anilist.co" -> "graphql.anilist.co"
        request.url.toString().startsWith(gateway + "/") && request.url.encodedPath in
            listOf("/schedule", "/schedule-recent", "/media", "/classifications") -> "graphql.anilist.co"
        request.url.toString().startsWith(gateway + "/release-schedule") -> "tsuzuki.top"
        request.url.toString().startsWith(gateway + "/") && request.url.encodedPath in
            listOf("/episodes", "/mapping") -> "api.ani.zip"
        request.url.toString().startsWith(gateway + "/artwork/tmdb-") -> "api.themoviedb.org"
        request.url.toString().startsWith(gateway + "/artwork/tvmaze-") -> "api.tvmaze.com"
        else -> request.url.host
    }

    private suspend fun gatewayGet(path: String): JsonObject? {
        if (!gateway.startsWith("https://")) return null
        val request = Request.Builder().url(gateway + path).build()
        val provider = providerFor(request)
        // A failing gateway must not add another timeout to every schedule page.
        if ((gatewayUnavailableUntil[provider] ?: 0) > clock()) return null
        return try { json.parseToJsonElement(this.request(request)).jsonObject }
        catch (e: AiringRateLimitException) { throw e }
        catch (e: CancellationException) { throw e }
        catch (e: AiringHttpException) {
            if (e.status == 404 && provider in listOf("api.ani.zip", "api.themoviedb.org", "api.tvmaze.com")) throw e
            gatewayUnavailableUntil[provider] = clock() + 60_000L
            null
        }
        catch (_: Exception) {
            gatewayUnavailableUntil[provider] = clock() + 60_000L
            null
        }
    }

    private suspend fun graphql(query: String, variables: JsonObject): JsonObject {
        val body = buildJsonObject { put("query", query); put("variables", variables) }.toString()
        val response = json.parseToJsonElement(request(Request.Builder()
            .url("https://graphql.anilist.co")
            .post(body.toRequestBody("application/json".toMediaType())).build())).jsonObject
        if ((response["errors"] as? JsonArray)?.isNotEmpty() == true) error("AniList could not return schedule data")
        return response["data"] as? JsonObject ?: error("AniList returned no data")
    }

    private suspend fun request(request: Request): String {
        val provider = providerFor(request)
        return requestMutexes.computeIfAbsent(provider) { Mutex() }.withLock {
            val gatewayRequest = request.url.toString().startsWith(gateway + "/")
            // A stale first page may still need further pages from the gateway cache.
            // The Worker enforces its upstream cooldown; direct access remains blocked.
            cooldowns[provider]?.takeIf { it > clock() &&
                !(gatewayRequest && (staleGatewayUntil[provider] ?: 0L) > clock())
            }?.let { throw AiringRateLimitException(it) }
            if (provider == "graphql.anilist.co") {
                if (gatewayRequest) {
                    // HIT pages do not spend upstream quota. MISS/legacy responses
                    // still pace the next request; direct requests have their own IP quota.
                    if (!gatewayCacheHit) pause((nextGatewayAniListAt - clock()).coerceAtLeast(0))
                    gatewayCacheHit = false
                    nextGatewayAniListAt = clock() + 2100L
                } else {
                    pause((nextDirectAniListAt - clock()).coerceAtLeast(0))
                    nextDirectAniListAt = clock() + 2100L
                }
            }
            execute(request, provider)
        }
    }

    private suspend fun execute(request: Request, provider: String): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request.newBuilder().header("Accept", "application/json")
            .header("User-Agent", "NuvioNative/1.0 (personal desktop schedule client)").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val payload = response.use {
                        if (provider == "graphql.anilist.co" && request.url.toString().startsWith(gateway + "/")) {
                            gatewayCacheHit = it.header("X-Nuvio-Cache") in listOf("HIT", "STALE")
                        }
                        val body = it.body ?: error("Schedule provider returned an empty response")
                        // Mapping lists are larger than paginated schedules; bound both.
                        body.byteStream().use { stream ->
                            val bytes = stream.readNBytes(12 * 1024 * 1024 + 1)
                            check(bytes.size <= 12 * 1024 * 1024) { "Schedule response is too large" }
                            val payload = bytes.toString(Charsets.UTF_8)
                            val gatewayLimited = it.code == 503 && runCatching {
                                json.parseToJsonElement(payload).jsonObject.number("providerStatus") == 429
                            }.getOrDefault(false)
                            val graphqlLimited = provider == "graphql.anilist.co" && runCatching {
                                (json.parseToJsonElement(payload).jsonObject["errors"] as? JsonArray).orEmpty()
                                    .any { error -> (error as? JsonObject)?.number("status") == 429 }
                            }.getOrDefault(false)
                            if (it.code == 429 || gatewayLimited || graphqlLimited || it.header("X-RateLimit-Remaining") == "0" || it.header("X-Nuvio-Stale") == "1") {
                                val reset = retryTime(it.headers, clock())
                                cooldowns.merge(provider, reset, ::maxOf)
                                if (it.header("X-Nuvio-Stale") == "1") staleGatewayUntil[provider] = reset
                                if (!it.isSuccessful || graphqlLimited) {
                                    staleGatewayUntil.remove(provider)
                                    throw AiringRateLimitException(reset)
                                }
                            }
                            if (!it.isSuccessful) throw AiringHttpException(it.code, request.url.host)
                            payload
                        }
                    }
                    if (continuation.isActive) continuation.resume(payload)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try { block() }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    companion object {
        private val MEDIA_FIELDS = "id idMal isAdult tags{name isAdult} title{english romaji native} coverImage{extraLarge large} bannerImage description genres episodes"
        private val SCHEDULE_QUERY = "query(\$start:Int,\$end:Int,\$page:Int){Page(page:\$page,perPage:50){pageInfo{hasNextPage}airingSchedules(airingAt_greater:\$start,airingAt_lesser:\$end,sort:TIME){episode airingAt media{$MEDIA_FIELDS}}}}"
        private val RECENT_QUERY = SCHEDULE_QUERY.replace("sort:TIME", "notYetAired:false,sort:TIME_DESC")
        private val FUTURE_QUERY = SCHEDULE_QUERY.replace("sort:TIME", "notYetAired:true,sort:TIME")
        private val CLASSIFICATION_QUERY = "query(\$ids:[Int]){Page(perPage:50){media(id_in:\$ids,type:ANIME){$MEDIA_FIELDS}}}"
        private val MEDIA_QUERY = "query(\$id:Int){Media(id:\$id,type:ANIME){$MEDIA_FIELDS}Page(perPage:50){airingSchedules(mediaId:\$id,notYetAired:false,sort:EPISODE_DESC){episode airingAt}}}"
    }
}

internal fun retryTime(headers: Headers, now: Long): Long {
    val retry = headers["Retry-After"]?.let { value ->
        value.toLongOrNull()?.takeIf { it >= 0 }?.let { now + it.coerceAtMost(86400L) * 1000L }
            ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    }
    val reset = headers["X-RateLimit-Reset"]?.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)
    return maxOf(now + 60_000L, retry ?: 0, reset ?: 0)
}

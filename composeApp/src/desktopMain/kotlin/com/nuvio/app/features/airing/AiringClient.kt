package com.nuvio.app.features.airing

import com.nuvio.app.core.network.DesktopIPv4FirstDns
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.details.MetaDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AiringClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().dns(DesktopIPv4FirstDns())
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()
    private val mappingMutex = Mutex()
    private var mappings: JsonArray? = null
    private var mappingSavedAt = 0L
    private val gateway = (System.getenv("NUVIO_AIRING_GATEWAY")
        ?: "https://nuvio-native-gateway.cloudflaredomainrrm.workers.dev").trimEnd('/')

    suspend fun schedule(settings: AiringSettings, now: Instant): List<AiringEvent> {
        val today = now.atZone(ZoneId.of(settings.zone)).toLocalDate()
        val first = today.minusDays((settings.historyDays - 1).toLong())
        if (settings.airType != "broadcast") {
            val url = "https://tsuzuki.top/api/v1/schedule?start=$first&days=${settings.historyDays + 2}&airType=${settings.airType}"
            val proxied = gatewayGet("/release-schedule?start=$first&days=${settings.historyDays + 2}&airType=${settings.airType}")
            val recent = parseTsuzukiSchedule(proxied ?: get(url), settings.airType)
            val week = gatewayGet("/release-schedule?start=$today&days=8&airType=${settings.airType}")
                ?: get("https://tsuzuki.top/api/v1/schedule?start=$today&days=8&airType=${settings.airType}")
            return (recent + parseTsuzukiSchedule(week, settings.airType)).distinctBy { "${it.mediaId}:${it.episode}" }
        }
        val result = mutableListOf<AiringEvent>()
        // Keep each request inside the gateway's existing start/range limits.
        for ((from, until) in listOf(first to today.plusDays(2), today.plusDays(1) to today.plusDays(8))) {
        val start = from.atStartOfDay(ZoneId.of(settings.zone)).toEpochSecond() - 1
        val end = until.atStartOfDay(ZoneId.of(settings.zone)).toEpochSecond()
        var complete = false
        for (number in 1..20) {
            val response = gatewayGet("/schedule?start=$start&end=$end&page=$number")?.obj("data")
                ?: graphql(SCHEDULE_QUERY, buildJsonObject {
                put("start", start); put("end", end); put("page", number)
            })
            val page = response.obj("Page")
            if (page["airingSchedules"] !is JsonArray) error("AniList returned no schedule")
            result.addAll(parseAniListSchedule(page))
            if (!page.obj("pageInfo").flag("hasNextPage")) { complete = true; break }
        }
        check(complete) { "Schedule is too large to load completely; choose fewer history days" }
        }
        return result.distinctBy { "${it.mediaId}:${it.episode}" }
    }

    suspend fun details(id: Int, known: List<AiringEvent>, settings: AiringSettings): MetaDetails? = coroutineScope {
        val existing = known.lastOrNull { it.mediaId == id && it.airType == settings.airType }
        val mediaRequest = async { optional {
            gatewayGet("/media?id=$id")?.obj("data")
                ?: graphql(MEDIA_QUERY, buildJsonObject { put("id", id) })
        } }
        val mappingRequest = async { optional { mapping(id) } }
        val extraRequest = async { optional {
            gatewayGet("/episodes?id=$id") ?: get("https://hayase.ani.zip/v1/episodes?anilist_id=$id")
        } }
        val metadata = mediaRequest.await()
        val info = metadata?.obj("Media")
        val extra = extraRequest.await() ?: JsonObject(emptyMap())
        val databaseMapping = mappingRequest.await() ?: JsonObject(emptyMap())
        val mapped = JsonObject(databaseMapping + extra.obj("mappings"))
        val record = info?.let { eventFromAniList(it, existing?.episode ?: 1, existing?.airingAt ?: 1) }
            ?.copy(airType = settings.airType) ?: existing ?: return@coroutineScope null
        val historical = (metadata?.obj("Page")?.get("airingSchedules") as? JsonArray).orEmpty().mapNotNull { element ->
            val node = element as? JsonObject ?: return@mapNotNull null
            record.copy(episode = node.number("episode") ?: return@mapNotNull null,
                airingAt = node.number("airingAt")?.toLong() ?: return@mapNotNull null)
        }.filter { it.episode in 1..10000 && it.airingAt > 0 }
        // AniList broadcast history must never claim a sub or dub episode is available.
        val events = if (settings.airType == "broadcast") historical + known else known
        buildAiringDetails(record, events, mapped, extra.obj("episodes"), Instant.now())
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
        request(Request.Builder().url(url).header("Cache-Control", "no-cache").build())
    ).jsonObject

    private suspend fun gatewayGet(path: String): JsonObject? =
        if (!gateway.startsWith("https://")) null else optional { get(gateway + path) }

    private suspend fun graphql(query: String, variables: JsonObject): JsonObject {
        val body = buildJsonObject { put("query", query); put("variables", variables) }.toString()
        val response = json.parseToJsonElement(request(Request.Builder()
            .url("https://graphql.anilist.co").header("Cache-Control", "no-cache")
            .post(body.toRequestBody("application/json".toMediaType())).build())).jsonObject
        if ((response["errors"] as? JsonArray)?.isNotEmpty() == true) error("AniList could not return schedule data")
        return response["data"] as? JsonObject ?: error("AniList returned no data")
    }

    private suspend fun request(request: Request): String = suspendCancellableCoroutine { continuation ->
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
                        if (!it.isSuccessful) {
                            val retry = if (it.code == 429) " (rate limited; will retry later)" else ""
                            error("${request.url.host}: HTTP ${it.code}$retry")
                        }
                        val body = it.body ?: error("Schedule provider returned an empty response")
                        // Mapping lists are larger than paginated schedules; bound both.
                        body.byteStream().use { stream ->
                            val bytes = stream.readNBytes(12 * 1024 * 1024 + 1)
                            check(bytes.size <= 12 * 1024 * 1024) { "Schedule response is too large" }
                            bytes.toString(Charsets.UTF_8)
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
        private val MEDIA_FIELDS = "id idMal title{english romaji native} coverImage{extraLarge large} bannerImage description genres episodes"
        private val SCHEDULE_QUERY = "query(\$start:Int,\$end:Int,\$page:Int){Page(page:\$page,perPage:50){pageInfo{hasNextPage}airingSchedules(airingAt_greater:\$start,airingAt_lesser:\$end,sort:TIME){episode airingAt media{$MEDIA_FIELDS}}}}"
        private val MEDIA_QUERY = "query(\$id:Int){Media(id:\$id,type:ANIME){$MEDIA_FIELDS}Page(perPage:50){airingSchedules(mediaId:\$id,notYetAired:false,sort:EPISODE_DESC){episode airingAt}}}"
    }
}

package com.nuvio.app.features.myanimelist

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class MalStatus(val value: String, val label: String) {
    WATCHING("watching", "Watching"), COMPLETED("completed", "Completed"), ON_HOLD("on_hold", "On Hold"),
    DROPPED("dropped", "Dropped"), PLAN_TO_WATCH("plan_to_watch", "Plan to Watch")
}
internal data class MalListStatus(
    val status: MalStatus?,
    val score: Int,
    val watchedEpisodes: Int,
    val isRewatching: Boolean = false,
)
internal data class MalAnime(val id: Int, val title: String)
internal data class MalAnimeListEntry(
    val anime: MalAnime,
    val imageUrl: String?,
    val totalEpisodes: Int,
    val listStatus: MalListStatus,
    /** Official English title, only when it differs from the main MAL title. */
    val englishTitle: String? = null,
    /** MAL media type, e.g. "tv", "movie", "ova". */
    val mediaType: String? = null,
    val meanScore: Double? = null,
    val startYear: Int? = null,
    val startSeason: String? = null,
    val airing: Boolean = false,
)
internal data class MalAnimeListPage(val entries: List<MalAnimeListEntry>, val nextOffset: Int?)

/** Server-side list orders supported by `users/@me/animelist`. */
internal enum class MalListSort(val value: String, val label: String) {
    UPDATED("list_updated_at", "Last updated"),
    TITLE("anime_title", "Title"),
    SCORE("list_score", "Your score"),
    START_DATE("anime_start_date", "Air date"),
}

internal data class MalAnimeStatistics(
    val watching: Int = 0,
    val completed: Int = 0,
    val onHold: Int = 0,
    val dropped: Int = 0,
    val planToWatch: Int = 0,
    val total: Int = 0,
    val episodes: Int = 0,
    val days: Double = 0.0,
    val meanScore: Double = 0.0,
) {
    fun count(status: MalStatus?): Int = when (status) {
        null -> total
        MalStatus.WATCHING -> watching
        MalStatus.COMPLETED -> completed
        MalStatus.ON_HOLD -> onHold
        MalStatus.DROPPED -> dropped
        MalStatus.PLAN_TO_WATCH -> planToWatch
    }
}

/** Upper bound for episode counts sent to MAL; long-running series stay well below it. */
internal const val MAX_MAL_EPISODES = 10_000

/** Never reset a completed title's progress or score when a new rewatch starts. */
internal fun shouldMarkMalRewatch(status: MalListStatus?): Boolean =
    status?.let { it.status == MalStatus.COMPLETED && !it.isRewatching } == true
@Serializable internal data class MalTokens(val clientId: String, val accessToken: String, val refreshToken: String,
    val expiresAt: Long, val username: String = "", val clientSecret: String = "")
internal class MalHttpException(val status: Int, errorCode: String? = null) : IOException(when (errorCode) {
    "invalid_client" -> "MyAnimeList rejected the API client. For a Web app, enter its client secret; for an Other app, leave it empty."
    "invalid_grant" -> "MyAnimeList rejected the authorization code. Check the registered redirect URL, then connect again."
    "invalid_request" -> "MyAnimeList rejected the sign-in request. Check the App Type and exact redirect URL in your MAL API settings."
    else -> "MyAnimeList returned HTTP $status. Please retry or reconnect."
})

internal class MalClient(
    private val api: String = "https://api.myanimelist.net/v2/",
    private val oauth: String = "https://myanimelist.net/v1/oauth2/",
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun exchange(clientId: String, code: String, verifier: String, redirect: String, clientSecret: String = ""): MalTokens =
        tokens(clientId, mapOf("grant_type" to "authorization_code", "code" to code, "code_verifier" to verifier, "redirect_uri" to redirect), clientSecret)
    suspend fun refresh(previous: MalTokens): MalTokens = tokens(previous.clientId,
        mapOf("grant_type" to "refresh_token", "refresh_token" to previous.refreshToken), previous.clientSecret).copy(username = previous.username)
    private suspend fun tokens(clientId: String, fields: Map<String, String>, clientSecret: String = ""): MalTokens {
        val body = FormBody.Builder().add("client_id", clientId).apply {
            if (clientSecret.isNotEmpty()) add("client_secret", clientSecret)
            fields.forEach { (key, value) -> add(key, value) }
        }.build()
        val response = request(Request.Builder().url(oauth + "token").post(body).build())
        val access = response["access_token"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it.length <= 8192 } ?: error("Invalid MAL token response")
        val refresh = response["refresh_token"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it.length <= 8192 } ?: error("Invalid MAL refresh response")
        val lifetime = response["expires_in"]?.jsonPrimitive?.longOrNull?.takeIf { it in 1..31_536_000 } ?: error("Invalid MAL token lifetime")
        return MalTokens(clientId, access, refresh, System.currentTimeMillis() + lifetime * 1000, clientSecret = clientSecret)
    }
    suspend fun username(token: String): String = get("users/@me", token)["name"]?.jsonPrimitive?.content ?: "Connected"
    suspend fun listStatus(id: Int, token: String): MalListStatus? {
        require(id > 0)
        return (get("anime/$id?fields=my_list_status", token)["my_list_status"] as? JsonObject)?.let(::parseMalListStatus)
    }
    suspend fun update(
        id: Int, token: String, status: MalStatus? = null, score: Int? = null,
        isRewatching: Boolean? = null, watchedEpisodes: Int? = null,
    ): MalListStatus {
        require(id > 0 && (status != null || score != null || isRewatching != null || watchedEpisodes != null))
        require(score == null || score in 0..10)
        require(watchedEpisodes == null || watchedEpisodes in 0..MAX_MAL_EPISODES)
        val body = FormBody.Builder().apply {
            status?.let { add("status", it.value) }
            score?.let { add("score", it.toString()) }
            isRewatching?.let { add("is_rewatching", it.toString()) }
            // MAL's write field is num_watched_episodes; responses call it num_episodes_watched.
            watchedEpisodes?.let { add("num_watched_episodes", it.toString()) }
        }.build()
        return parseMalListStatus(request(Request.Builder().url(api + "anime/$id/my_list_status")
            .header("Authorization", "Bearer $token").patch(body).build()))
    }

    /** Removes the title from the user's list. A title that is already gone counts as removed. */
    suspend fun remove(id: Int, token: String) {
        require(id > 0)
        try {
            call(Request.Builder().url(api + "anime/$id/my_list_status")
                .header("Authorization", "Bearer $token").delete().build())
        } catch (e: MalHttpException) {
            if (e.status != 404) throw e
        }
    }

    suspend fun animeStatistics(token: String): MalAnimeStatistics {
        val stats = get("users/@me?fields=anime_statistics", token)["anime_statistics"] as? JsonObject
            ?: return MalAnimeStatistics()
        fun int(name: String) = stats[name]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0
        fun double(name: String) = stats[name]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
        return MalAnimeStatistics(
            watching = int("num_items_watching"),
            completed = int("num_items_completed"),
            onHold = int("num_items_on_hold"),
            dropped = int("num_items_dropped"),
            planToWatch = int("num_items_plan_to_watch"),
            total = int("num_items"),
            episodes = int("num_episodes"),
            days = double("num_days"),
            meanScore = double("mean_score"),
        )
    }

    suspend fun animeList(
        token: String, status: MalStatus? = null, offset: Int = 0, limit: Int = 60,
        sort: MalListSort = MalListSort.UPDATED,
    ): MalAnimeListPage {
        require(offset in 0..100_000 && limit in 1..100)
        val url = (api + "users/@me/animelist").toHttpUrl().newBuilder()
            .addQueryParameter("fields", "list_status,num_episodes,alternative_titles,mean,media_type,start_season,status")
            .addQueryParameter("sort", sort.value)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .apply { status?.let { addQueryParameter("status", it.value) } }
            .build()
        val response = request(Request.Builder().url(url).header("Authorization", "Bearer $token").build())
        val entries = (response["data"] as? JsonArray).orEmpty().mapNotNull { raw ->
            val row = raw as? JsonObject ?: return@mapNotNull null
            val node = row["node"] as? JsonObject ?: return@mapNotNull null
            val id = node["id"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
            val listStatus = (row["list_status"] as? JsonObject)?.let(::parseMalListStatus) ?: return@mapNotNull null
            val picture = node["main_picture"] as? JsonObject
            val title = node["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "Anime $id" }
            val season = node["start_season"] as? JsonObject
            MalAnimeListEntry(
                anime = MalAnime(id, title),
                imageUrl = picture?.get("large")?.jsonPrimitive?.contentOrNull
                    ?: picture?.get("medium")?.jsonPrimitive?.contentOrNull,
                totalEpisodes = node["num_episodes"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0,
                listStatus = listStatus,
                englishTitle = ((node["alternative_titles"] as? JsonObject)?.get("en") as? JsonPrimitive)
                    ?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && !it.equals(title, ignoreCase = true) },
                mediaType = (node["media_type"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "unknown" },
                meanScore = (node["mean"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it in 0.01..10.0 },
                startYear = (season?.get("year") as? JsonPrimitive)?.intOrNull?.takeIf { it in 1900..2200 },
                startSeason = (season?.get("season") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
                airing = (node["status"] as? JsonPrimitive)?.contentOrNull == "currently_airing",
            )
        }
        val hasNext = (response["paging"] as? JsonObject)?.get("next")?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
        return MalAnimeListPage(entries, if (hasNext && entries.isNotEmpty()) offset + limit else null)
    }
    suspend fun search(name: String, token: String): List<MalAnime> {
        val url = (api + "anime").toHttpUrl().newBuilder().addQueryParameter("q", name.take(64)).addQueryParameter("limit", "10").build()
        return (request(Request.Builder().url(url).header("Authorization", "Bearer $token").build())["data"] as? JsonArray).orEmpty()
            .mapNotNull { val node = (it as? JsonObject)?.get("node") as? JsonObject ?: return@mapNotNull null
                val id = node["id"]?.jsonPrimitive?.intOrNull?.takeIf { value -> value > 0 } ?: return@mapNotNull null
                MalAnime(id, node["title"]?.jsonPrimitive?.content ?: "Anime $id") }
    }
    private suspend fun get(path: String, token: String) = request(Request.Builder().url(api + path).header("Authorization", "Bearer $token").build())
    private suspend fun request(request: Request): JsonObject =
        call(request) as? JsonObject ?: throw IOException("Unexpected MyAnimeList response")

    /** Returns the parsed JSON body, or null when the response has no body (e.g. a DELETE). */
    private suspend fun call(request: Request): JsonElement? = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request.newBuilder().header("Accept", "application/json").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (!it.isSuccessful) {
                            val errorCode = runCatching {
                                json.parseToJsonElement(it.peekBody(4096).string()).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                            }.getOrNull()
                            throw MalHttpException(it.code, errorCode)
                        }
                        val bytes = it.body?.byteStream()?.use { stream -> stream.readNBytes(2 * 1024 * 1024 + 1) } ?: ByteArray(0)
                        check(bytes.size <= 2 * 1024 * 1024) { "MAL response is too large" }
                        val text = bytes.toString(Charsets.UTF_8)
                        if (text.isBlank()) null else json.parseToJsonElement(text)
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
}
internal fun parseMalListStatus(value: JsonObject) = MalListStatus(
    MalStatus.entries.firstOrNull { it.value == value["status"]?.jsonPrimitive?.contentOrNull },
    value["score"]?.jsonPrimitive?.intOrNull?.coerceIn(0, 10) ?: 0,
    value["num_episodes_watched"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0,
    value["is_rewatching"]?.jsonPrimitive?.booleanOrNull ?: false,
)

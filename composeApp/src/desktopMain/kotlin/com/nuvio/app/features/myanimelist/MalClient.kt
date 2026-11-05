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
)
internal data class MalAnimeListPage(val entries: List<MalAnimeListEntry>, val nextOffset: Int?)

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
        isRewatching: Boolean? = null,
    ): MalListStatus {
        require(id > 0 && (status != null || score != null || isRewatching != null) && (score == null || score in 0..10))
        val body = FormBody.Builder().apply {
            status?.let { add("status", it.value) }
            score?.let { add("score", it.toString()) }
            isRewatching?.let { add("is_rewatching", it.toString()) }
        }.build()
        return parseMalListStatus(request(Request.Builder().url(api + "anime/$id/my_list_status")
            .header("Authorization", "Bearer $token").patch(body).build()))
    }
    suspend fun animeList(token: String, status: MalStatus? = null, offset: Int = 0, limit: Int = 60): MalAnimeListPage {
        require(offset in 0..100_000 && limit in 1..100)
        val url = (api + "users/@me/animelist").toHttpUrl().newBuilder()
            .addQueryParameter("fields", "list_status,num_episodes")
            .addQueryParameter("sort", "list_updated_at")
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
            MalAnimeListEntry(
                anime = MalAnime(id, node["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "Anime $id" }),
                imageUrl = picture?.get("medium")?.jsonPrimitive?.contentOrNull
                    ?: picture?.get("large")?.jsonPrimitive?.contentOrNull,
                totalEpisodes = node["num_episodes"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0,
                listStatus = listStatus,
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
    private suspend fun request(request: Request): JsonObject = suspendCancellableCoroutine { continuation ->
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
                        val bytes = it.body?.byteStream()?.use { stream -> stream.readNBytes(2 * 1024 * 1024 + 1) } ?: error("Empty MAL response")
                        check(bytes.size <= 2 * 1024 * 1024) { "MAL response is too large" }
                        json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
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

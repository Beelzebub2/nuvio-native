package com.nuvio.app.features.remote

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal actual fun createStarlitTvPlaybackTransport(): StarlitTvPlaybackTransport =
    AndroidStarlitTvPlaybackTransport()

/**
 * A native phone joins the same on-TV-approved short-lived session as the
 * first-party QR page. The relay stores descriptions only, not media URLs.
 * Its claim token lives only in process memory and is never persisted.
 */
internal class AndroidStarlitTvPlaybackTransport : StarlitTvPlaybackTransport {
    override val supported = true
    private val root = "https://starlit.rrmtools.uk/tv/api/"
    private val siteOrigin = "https://starlit.rrmtools.uk"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong()
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private data class Session(val id: String, val token: String, val name: String)
    @Volatile private var session: Session? = null
    @Volatile private var polling: Job? = null
    @Volatile private var latestRequestId = ""
    @Volatile private var pendingHandoff: RemotePlaybackHandoff? = null

    override fun pair(code: String) {
        val normalized = code.trim().uppercase()
        if (!Regex("[A-HJ-NP-Z2-9]{8}").matches(normalized)) {
            StarlitTvPlayback.error.value = true
            StarlitTvPlayback.status.value = "invalid-code"
            return
        }
        disconnect()
        val current = generation.incrementAndGet()
        StarlitTvPlayback.pairing.value = true
        StarlitTvPlayback.status.value = "waiting-approval"
        StarlitTvPlayback.error.value = false
        polling = scope.launch {
            try {
                val result = request("claim", "POST", JSONObject().put("code", normalized), null)
                if (!valid(current)) return@launch
                val id = result.optString("id")
                val token = result.optString("claimToken")
                val verify = result.optString("verification")
                require(Regex("[0-9a-f]{64}").matches(id) &&
                    Regex("[0-9a-f]{64}").matches(token) &&
                    Regex("[A-HJ-NP-Z2-9]{6}").matches(verify)) { "Invalid TV pairing response" }
                session = Session(id, token, result.optString("name").take(64))
                StarlitTvPlayback.verification.value = verify
                while (isActive && valid(current)) {
                    val connected = session ?: break
                    val state = request("phone", "GET", null, connected)
                    if (!valid(current)) break
                    if (state.optBoolean("approved")) {
                        StarlitTvPlayback.connected.value = true
                        StarlitTvPlayback.pairing.value = false
                        StarlitTvPlayback.name.value = connected.name
                        StarlitTvPlayback.status.value = "connected"
                        readState(state.optJSONObject("state"))
                    }
                    delay(1800)
                }
            } catch (_: CancellationException) {
                throw CancellationException("TV pairing cancelled")
            } catch (_: Exception) {
                if (valid(current)) {
                    StarlitTvPlayback.error.value = true
                    StarlitTvPlayback.status.value = "connection-failed"
                    StarlitTvPlayback.pairing.value = false
                    StarlitTvPlayback.connected.value = false
                    StarlitTvPlayback.picker.value = RemoteSourcePicker(
                        requestId = latestRequestId, phase = "error")
                    session = null
                }
            }
        }
    }

    override fun request(handoff: RemotePlaybackHandoff): Boolean {
        if (!StarlitTvPlayback.connected.value || session == null || !handoff.isAllowed()) return false
        val requestId = newRemotePlaybackId()
        latestRequestId = requestId
        pendingHandoff = handoff
        StarlitTvPlayback.picker.value = RemoteSourcePicker(
            requestId = requestId, title = handoff.title, phase = "searching")
        StarlitTvPlayback.pickerVisible.value = true
        sendCommand("handoffSearch", JSONObject().put("media", JSONObject()
            .put("requestId", requestId)
            .put("contentType", handoff.contentType)
            .put("videoId", handoff.videoId)
            .put("parentMetaId", handoff.parentMetaId)
            .put("title", handoff.title)
            .put("season", handoff.season ?: JSONObject.NULL)
            .put("episode", handoff.episode ?: JSONObject.NULL)
            .put("resumePositionMs", handoff.resumePositionMs)))
        return true
    }

    override fun select(sourceId: String) {
        val state = StarlitTvPlayback.picker.value
        if (!StarlitTvPlayback.connected.value || session == null ||
            state.requestId != latestRequestId ||
            state.phase !in setOf("ready", "searching") ||
            state.sources.none { it.id == sourceId }) return
        StarlitTvPlayback.picker.value = state.copy(phase = "preparing")
        sendCommand("handoffSelect", JSONObject()
            .put("requestId", latestRequestId).put("sourceId", sourceId))
    }

    override fun cancelSourcePicker() {
        val id = latestRequestId
        StarlitTvPlayback.pickerVisible.value = false
        latestRequestId = ""
        if (id.isNotEmpty() && StarlitTvPlayback.connected.value) {
            sendCommand("handoffCancel", JSONObject().put("requestId", id))
        }
    }

    override fun retrySourcePicker(): Boolean =
        pendingHandoff?.let(::request) == true

    private fun sendCommand(op: String, payload: JSONObject) {
        val snapshot = session ?: return
        val current = generation.get()
        scope.launch {
            try {
                request("command", "POST", payload.put("op", op), snapshot)
            } catch (_: Exception) {
                if (valid(current) && snapshot == session) {
                    val state = StarlitTvPlayback.picker.value
                    if (state.requestId == latestRequestId && op != "handoffCancel") {
                        StarlitTvPlayback.picker.value =
                            state.copy(phase = "error", message = "TV could not receive the request.")
                    }
                }
            }
        }
    }

    private fun readState(raw: JSONObject?) {
        val source = raw?.optJSONObject("sourcePicker") ?: return
        val id = source.optString("requestId")
        if (latestRequestId.isEmpty() || latestRequestId != id) return
        val phase = source.optString("phase")
        val remoteChoices = source.optJSONArray("sources") ?: JSONArray()
        val choices = buildList {
            for (index in 0 until minOf(remoteChoices.length(), 36)) {
                val option = remoteChoices.optJSONObject(index) ?: continue
                val handle = option.optString("id")
                if (!Regex("source:[1-9][0-9]{0,3}").matches(handle)) continue
                add(RemoteChoice(
                    id = handle, label = option.optString("label").take(120),
                    quality = option.optString("quality").take(24).ifBlank { null },
                    provider = option.optString("provider").take(64).ifBlank { null },
                ))
            }
        }
        if (phase !in setOf("searching", "ready", "empty", "error", "starting", "playing")) return
        val effectivePhase = if (phase == "starting") "preparing" else phase
        StarlitTvPlayback.picker.value = RemoteSourcePicker(
            requestId = id,
            title = source.optString("title").take(160),
            phase = effectivePhase,
            sources = choices,
            message = source.optString("message").take(160),
        )
    }

    private fun request(path: String, method: String, payload: JSONObject?, current: Session?): JSONObject {
        val suffix = current?.let { "?id=${it.id}" } ?: ""
        val builder = Request.Builder().url(root + path + suffix)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
        if (current != null) builder.header("X-Phone-Token", current.token)
        if (method == "POST") {
            builder.header("Origin", siteOrigin)
            builder.post((payload ?: JSONObject()).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType()))
        } else builder.get()
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("TV pairing or command rejected: ${response.code}")
            val body = response.body?.string()?.take(65536) ?: throw IOException("Empty TV response")
            return JSONObject(body)
        }
    }

    private fun valid(expected: Long) = generation.get() == expected

    override fun disconnect() {
        generation.incrementAndGet()
        polling?.cancel()
        polling = null
        val previous = session
        session = null
        latestRequestId = ""
        pendingHandoff = null
        StarlitTvPlayback.connected.value = false
        StarlitTvPlayback.pairing.value = false
        StarlitTvPlayback.verification.value = ""
        StarlitTvPlayback.name.value = ""
        StarlitTvPlayback.pickerVisible.value = false
        StarlitTvPlayback.picker.value = RemoteSourcePicker()
        StarlitTvPlayback.error.value = false
        StarlitTvPlayback.status.value = ""
        if (previous != null) scope.launch {
            runCatching { request("leave", "POST", JSONObject(), previous) }
        }
    }
}

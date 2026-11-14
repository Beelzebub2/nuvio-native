package com.nuvio.app.features.remote

import android.content.Context
import android.util.Base64
import com.nuvio.app.core.storage.AndroidCredentialProtection
import com.nuvio.app.core.storage.PlatformLocalAccountDataCleaner
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

internal actual fun createSmartTvRemoteTransport(): SmartTvRemoteTransport = AndroidSmartTvRemoteTransport()

@Serializable
internal data class StoredSmartTv(
    val address: String,
    val platform: SmartTvPlatform,
    val name: String,
    val key: String = "",
    val fingerprint: String = "",
) {
    fun device() = SmartTvDevice(address, platform, name)
}

/** Secrets and the first-seen TV certificate fingerprint are kept in Android Keystore-backed storage. */
private object SmartTvTrust {
    private const val STORAGE_KEY = "starlit.smart-tv-remote.v1"
    private const val PREFS = "starlit_smart_tv_remote"
    private val json = Json { ignoreUnknownKeys = true }
    private fun prefs() = PlatformLocalAccountDataCleaner.appContext
        ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(): List<StoredSmartTv> = runCatching {
        val ciphertext = prefs()?.getString(STORAGE_KEY, null) ?: return emptyList()
        if (!AndroidCredentialProtection.isProtected(ciphertext)) return emptyList()
        json.decodeFromString<List<StoredSmartTv>>(AndroidCredentialProtection.reveal(STORAGE_KEY, ciphertext))
            .filter { isPrivateSmartTvAddress(it.address) && it.fingerprint.length == 64 }
    }.getOrDefault(emptyList())

    fun write(values: List<StoredSmartTv>): Boolean = runCatching {
        val ciphertext = AndroidCredentialProtection.protect(STORAGE_KEY, json.encodeToString(values))
        prefs()?.edit()?.putString(STORAGE_KEY, ciphertext)?.commit() == true
    }.getOrDefault(false)
}

/**
 * Samsung's remote channel and LG's SSAP channel use the TV's own approval dialog.
 * Both use certificate TOFU because retail TVs ship with a self-signed certificate.
 * A changed certificate is blocked until the user explicitly forgets and re-pairs.
 */
internal class AndroidSmartTvRemoteTransport : SmartTvRemoteTransport {
    override val supported = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sequence = AtomicLong()
    private val requestIds = AtomicLong()
    private val trustLock = Any()
    @Volatile private var currentGeneration = 0L
    @Volatile private var connectingDevice: SmartTvDevice? = null
    @Volatile private var expectedFingerprint = ""
    @Volatile private var receivedFingerprint = ""
    private var socket: WebSocket? = null
    private var pointerSocket: WebSocket? = null
    private var plainPointer: LocalLgPointerSocket? = null

    private val trustManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) =
            throw CertificateException("Client certificates are not accepted")

        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("No TV certificate")
            val fingerprint = sha256Fingerprint(leaf.encoded)
            synchronized(trustLock) {
                if (connectingDevice == null) throw CertificateException("No active TV pairing")
                if (expectedFingerprint.isNotEmpty() && fingerprint != expectedFingerprint) {
                    throw CertificateException("TV certificate changed; forget the TV before pairing again")
                }
                if (receivedFingerprint.isNotEmpty() && fingerprint != receivedFingerprint) {
                    throw CertificateException("TV presented conflicting certificates")
                }
                receivedFingerprint = fingerprint
            }
        }
    }

    private val http: OkHttpClient by lazy {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustManager), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier { host, _ ->
                // Do not allow the pinned-local-device trust manager to become a generic TLS bypass.
                isPrivateSmartTvAddress(host) && host == connectingDevice?.address
            }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    init {
        refreshSaved()
    }

    override fun discover() {
        if (SmartTvRemote.scanning.value) return
        SmartTvRemote.scanning.value = true
        SmartTvRemote.status.value = "Searching the local network for Samsung and LG TVs…"
        scope.launch {
            try {
                SmartTvRemote.discovered.value = discoverSsdpSmartTvs()
                SmartTvRemote.status.value = if (SmartTvRemote.discovered.value.isEmpty()) {
                    "No TVs found. Try entering a TV's local IP address."
                } else "Found ${SmartTvRemote.discovered.value.size} TVs. Select one to pair."
            } catch (_: Exception) {
                SmartTvRemote.status.value = "TV search was blocked. Connect using the TV's IP address."
            } finally {
                SmartTvRemote.scanning.value = false
            }
        }
    }

    override fun connect(device: SmartTvDevice) {
        if (!isPrivateSmartTvAddress(device.address)) {
            SmartTvRemote.status.value = "Enter a valid private-network IPv4 address."
            return
        }
        clearSession()
        val generation = sequence.incrementAndGet()
        currentGeneration = generation
        val stored = SmartTvTrust.read().firstOrNull {
            it.address == device.address && it.platform == device.platform
        }
        connectingDevice = device
        synchronized(trustLock) {
            expectedFingerprint = stored?.fingerprint.orEmpty()
            receivedFingerprint = ""
        }
        SmartTvRemote.active.value = null
        SmartTvRemote.connecting.value = true
        SmartTvRemote.status.value = "Connecting to ${device.name}…"
        val url = when (device.platform) {
            SmartTvPlatform.SamsungTizen -> {
                val name = Base64.encodeToString("Starlit Remote".toByteArray(), Base64.NO_WRAP)
                val token = stored?.key.orEmpty()
                "wss://${device.address}:8002/api/v2/channels/samsung.remote.control" +
                    "?name=${URLEncoder.encode(name, "UTF-8")}" +
                    (if (token.isNotBlank()) "&token=${URLEncoder.encode(token, "UTF-8")}" else "")
            }
            SmartTvPlatform.LgWebOs -> "wss://${device.address}:3001/"
        }
        socket = http.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!isCurrent(generation)) return
                    SmartTvRemote.status.value = "Approve Starlit Remote on the TV screen."
                    if (device.platform == SmartTvPlatform.LgWebOs) {
                        webSocket.send(lgRegistrationMessage(stored?.key.orEmpty()))
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!isCurrent(generation)) return
                    runCatching {
                        val payload = JSONObject(text)
                        if (device.platform == SmartTvPlatform.SamsungTizen) {
                            when (payload.optString("event")) {
                                "ms.channel.connect" -> {
                                    val token = payload.optJSONObject("data")?.optString("token").orEmpty()
                                        .ifBlank { stored?.key.orEmpty() }
                                    authenticated(device, token, generation)
                                }
                                "ms.channel.unauthorized" -> {
                                    SmartTvRemote.status.value = "Allow the remote connection in the TV prompt."
                                }
                                "ms.channel.disconnect" -> connectionLost(generation, "TV disconnected.")
                            }
                        } else {
                            when (payload.optString("type")) {
                                "registered" -> {
                                    val key = payload.optJSONObject("payload")?.optString("client-key").orEmpty()
                                        .ifBlank { stored?.key.orEmpty() }
                                    authenticated(device, key, generation)
                                    requestPointerSocket(webSocket)
                                }
                                "error" -> connectionLost(generation, "TV denied the remote pairing request.")
                                "response" -> {
                                    val id = payload.optString("id")
                                    if (id == "starlit-pointer") {
                                        val path = payload.optJSONObject("payload")?.optString("socketPath").orEmpty()
                                        connectPointer(device, path, generation)
                                    } else if (id.startsWith("starlit-mute-")) {
                                        val muted = payload.optJSONObject("payload")?.optBoolean("muted") ?: false
                                        lgRequest(webSocket, "ssap://audio/setMute", JSONObject().put("mute", !muted))
                                    }
                                }
                            }
                        }
                    }.onFailure {
                        // TVs can send informational frames outside the commands we handle.
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    connectionLost(generation, remoteError(t))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    connectionLost(generation, "TV connection closed.")
                }
            },
        )
        scope.launch {
            delay(30_000)
            if (isCurrent(generation) && SmartTvRemote.connecting.value) {
                connectionLost(generation, "TV approval timed out. Check the TV screen and retry.")
            }
        }
    }

    private fun authenticated(device: SmartTvDevice, key: String, generation: Long) {
        if (!isCurrent(generation)) return
        // The on-TV approval must complete before the untrusted first-seen certificate is saved.
        val fingerprint = synchronized(trustLock) { receivedFingerprint }
        if (fingerprint.length != 64) {
            connectionLost(generation, "Could not verify the TV's certificate.")
            return
        }
        val record = StoredSmartTv(device.address, device.platform, device.name, key, fingerprint)
        val updated = SmartTvTrust.read().filterNot {
            it.address == device.address && it.platform == device.platform
        } + record
        val saved = SmartTvTrust.write(updated)
        refreshSaved()
        SmartTvRemote.active.value = device
        SmartTvRemote.connecting.value = false
        SmartTvRemote.status.value = if (!saved) {
            "Connected, but pairing could not be saved on this phone."
        } else if (key.isBlank()) {
            "Connected. This TV did not provide a reusable pairing token; you may need to approve again."
        } else {
            "Connected to ${device.name}."
        }
    }

    private fun requestPointerSocket(webSocket: WebSocket) {
        lgRequest(webSocket, "ssap://com.webos.service.networkinput/getPointerInputSocket", id = "starlit-pointer")
    }

    private fun connectPointer(device: SmartTvDevice, path: String, generation: Long) {
        if (!isCurrent(generation)) return
        val uri = runCatching { URI(path) }.getOrNull()
        if (uri == null || uri.host != device.address || uri.userInfo != null ||
            uri.scheme !in listOf("ws", "wss") ||
            (uri.scheme == "ws" && uri.port != 3000) ||
            (uri.scheme == "wss" && uri.port != 3001)) {
            SmartTvRemote.status.value = "TV navigation socket was not trusted; reconnect to retry."
            return
        }
        if (uri.scheme == "ws") {
            scope.launch {
                try {
                    val pointer = LocalLgPointerSocket(uri)
                    pointer.open()
                    if (isCurrent(generation)) {
                        plainPointer = pointer
                        SmartTvRemote.status.value = "Connected to ${device.name}."
                    } else pointer.close()
                } catch (_: Exception) {
                    if (isCurrent(generation)) SmartTvRemote.status.value = "TV navigation channel unavailable."
                }
            }
            return
        }
        pointerSocket = http.newWebSocket(Request.Builder().url(path).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (isCurrent(generation)) SmartTvRemote.status.value = "Connected to ${device.name}."
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (isCurrent(generation)) SmartTvRemote.status.value = "TV navigation channel unavailable."
            }
        })
    }

    override fun send(key: SmartTvKey) {
        val device = SmartTvRemote.active.value ?: return
        val activeSocket = socket ?: return
        when (device.platform) {
            SmartTvPlatform.SamsungTizen -> {
                val code = key.samsung ?: return
                activeSocket.send(
                    JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
                        .put("Cmd", "Click").put("DataOfCmd", code)
                        .put("Option", "false").put("TypeOfRemote", "SendRemoteKey")).toString(),
                )
            }
            SmartTvPlatform.LgWebOs -> {
                when {
                    key.lgPointer != null -> {
                        val body = "type:button\nname:${key.lgPointer}\n\n"
                        when {
                            pointerSocket != null -> pointerSocket?.send(body)
                            plainPointer != null -> scope.launch {
                                runCatching { plainPointer?.send(body) }
                                    .onFailure { SmartTvRemote.status.value = "TV navigation disconnected." }
                            }
                            else -> SmartTvRemote.status.value = "TV navigation is still connecting."
                        }
                    }
                    key == SmartTvKey.Mute -> lgRequest(activeSocket, "ssap://audio/getVolume",
                        id = "starlit-mute-${requestIds.incrementAndGet()}")
                    key.lgSsap != null -> lgRequest(activeSocket, key.lgSsap)
                }
            }
        }
    }

    override fun forget(device: SmartTvDevice) {
        if (connectingDevice == device) disconnect()
        SmartTvTrust.write(SmartTvTrust.read().filterNot {
            it.address == device.address && it.platform == device.platform
        })
        refreshSaved()
    }

    override fun disconnect() {
        clearSession()
        SmartTvRemote.status.value = "Disconnected from TV."
    }

    private fun clearSession() {
        currentGeneration = sequence.incrementAndGet()
        socket?.close(1000, "Starlit remote disconnected")
        pointerSocket?.close(1000, "Starlit remote disconnected")
        plainPointer?.close()
        socket = null
        pointerSocket = null
        plainPointer = null
        connectingDevice = null
        synchronized(trustLock) {
            expectedFingerprint = ""
            receivedFingerprint = ""
        }
        SmartTvRemote.active.value = null
        SmartTvRemote.connecting.value = false
    }

    private fun connectionLost(generation: Long, message: String) {
        if (!isCurrent(generation)) return
        clearSession()
        SmartTvRemote.status.value = message
    }
    private fun isCurrent(generation: Long) = currentGeneration == generation
    private fun refreshSaved() { SmartTvRemote.saved.value = SmartTvTrust.read().map(StoredSmartTv::device) }
}

private fun sha256Fingerprint(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun remoteError(error: Throwable): String = when {
    error.message?.contains("certificate", ignoreCase = true) == true ||
        error.message?.contains("trust", ignoreCase = true) == true ->
        "TV certificate was rejected. If it changed, forget the TV and pair it again."
    else -> "Could not connect to the TV. Check Wi-Fi, remote access and TV approval."
}

private fun lgRequest(
    webSocket: WebSocket,
    uri: String,
    payload: JSONObject? = null,
    id: String = "starlit-command",
) {
    val request = JSONObject().put("type", "request").put("id", id).put("uri", uri)
    if (payload != null) request.put("payload", payload)
    webSocket.send(request.toString())
}

private fun lgRegistrationMessage(key: String): String {
    val permissions = JSONArray(listOf(
        "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_AUDIO", "CONTROL_INPUT_MEDIA_PLAYBACK",
    ))
    val manifest = JSONObject()
        .put("manifestVersion", 1)
        .put("appVersion", "1.0")
        .put("permissions", permissions)
        .put("signed", JSONObject().put("created", "20261114")
            .put("appId", "com.starlit.remote").put("vendorId", "com.starlit"))
    val payload = JSONObject().put("pairingType", "PROMPT").put("manifest", manifest)
    if (key.isNotBlank()) payload.put("client-key", key)
    return JSONObject().put("type", "register").put("id", "starlit-register")
        .put("payload", payload).toString()
}

/** SSDP is best-effort: multicast is often blocked by AP isolation or guest Wi-Fi. */
private fun discoverSsdpSmartTvs(): List<SmartTvDevice> {
    val found = linkedMapOf<String, SmartTvDevice>()
    val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces()).filter {
        it.isUp && !it.isLoopback && !it.isVirtual &&
            (it.name.startsWith("wlan", ignoreCase = true) || it.name.startsWith("eth", ignoreCase = true))
    }
    val search = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\nMX: 1\r\nST: ssdp:all\r\n\r\n").toByteArray()
    for (network in interfaces) {
        for (local in Collections.list(network.inetAddresses)) {
            if (local !is Inet4Address || !isPrivateSmartTvAddress(local.hostAddress ?: "")) continue
            runCatching {
                DatagramSocket(InetSocketAddress(local, 0)).use { socket ->
                    socket.soTimeout = 250
                    socket.send(DatagramPacket(search, search.size,
                        InetAddress.getByName("239.255.255.250"), 1900))
                    val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2400)
                    while (System.nanoTime() < until) {
                        val buffer = ByteArray(8192)
                        val packet = DatagramPacket(buffer, buffer.size)
                        try { socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { continue }
                        val remote = packet.address.hostAddress ?: continue
                        if (!isPrivateSmartTvAddress(remote)) continue
                        val description = String(packet.data, packet.offset, packet.length).lowercase()
                        val vendor = when {
                            description.contains("samsung") || description.contains("tizen") ->
                                SmartTvPlatform.SamsungTizen
                            description.contains("webos") || description.contains("lge") ||
                                description.contains("lg smart") -> SmartTvPlatform.LgWebOs
                            else -> continue
                        }
                        found["$remote:${vendor.name}"] = SmartTvDevice(remote, vendor)
                    }
                }
            }
        }
    }
    return found.values.toList().sortedWith(compareBy({ it.platform.name }, { it.address }))
}

package com.nuvio.app.features.remote

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val DISCOVERY_PORT = 47788
private const val PAIRING_TIMEOUT = 90_000L
private val wireJson = Json { ignoreUnknownKeys = true }

// A nonce-bound discovery filter, never an authentication proof; account verification follows pairing.
private fun discoveryAccountHint(account: RemoteAccount, nonce: String): String =
    remoteHash("${account.server}\u0000${account.userId}".toByteArray(Charsets.UTF_8), nonce.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal fun sameRemoteSubnet(peer: ByteArray, local: ByteArray, prefix: Int): Boolean {
    if (peer.size != 4 || local.size != 4 || prefix !in 1..32) return false
    return (0 until prefix).all { bit ->
        val mask = 1 shl (7 - bit % 8)
        (peer[bit / 8].toInt() and mask) == (local[bit / 8].toInt() and mask)
    }
}

private fun localInterfaces() = Collections.list(NetworkInterface.getNetworkInterfaces()).filter {
    it.isUp && !it.isLoopback && !it.isVirtual
}.flatMap { it.interfaceAddresses }.filter { it.address is Inet4Address && it.address.isSiteLocalAddress }

internal fun isRemoteLanPeer(address: InetAddress): Boolean = address is Inet4Address && address.isSiteLocalAddress &&
    localInterfaces().any { sameRemoteSubnet(address.address, it.address.address, it.networkPrefixLength.toInt()) }

// Some Android Wi-Fi interfaces report no directed broadcast address. A single
// limited-broadcast fallback keeps discovery usable without broadening the peer
// subnet checks or exposing any privileged remote commands.
internal fun remoteBroadcastTargets(interfaceBroadcasts: List<InetAddress>): List<InetAddress> =
    (interfaceBroadcasts + InetAddress.getByName("255.255.255.255")).distinct()

/** No HTTP listener: all LAN payloads after the committed handshake use AES-GCM. */
internal class LanDesktopRemote : DesktopRemoteTransport {
    override val supported = true
    @Volatile private var scope: CoroutineScope? = null
    private var account: RemoteAccount? = null
    private var host = false
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var discovery: DatagramSocket? = null
    @Volatile private var scanSocket: DatagramSocket? = null
    @Volatile private var connection: Job? = null
    @Volatile private var confirmation: CompletableDeferred<Unit>? = null
    private var commands = Channel<RemoteCommand>(32)
    private val sessionEpoch = AtomicLong()

    override fun start(account: RemoteAccount, host: Boolean) {
        stop()
        this.account = account
        this.host = host
        val currentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = currentScope
        if (host) currentScope.launch { listen(account, currentScope) }
    }

    override fun stop() {
        scope?.cancel()
        scope = null
        account = null
        disconnect()
        runCatching { server?.close() }
        runCatching { discovery?.close() }
        runCatching { scanSocket?.close() }
        server = null
        discovery = null
        scanSocket = null
        DesktopRemote.desktops.value = emptyList()
        DesktopRemote.scanning.value = false
    }

    override fun disconnect() {
        sessionEpoch.incrementAndGet()
        connection?.cancel()
        connection = null
        confirmation?.cancel()
        confirmation = null
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        commands.close()
        commands = Channel(32)
        DesktopRemote.pairing.value = null
        DesktopRemote.connected.value = false
        DesktopRemote.remotePlayback.value = RemotePlayback()
    }

    override fun confirmPairing() { confirmation?.complete(Unit) }
    override fun send(command: RemoteCommand) {
        if (!commands.trySend(command).isSuccess) DesktopRemote.status.value = "Remote is busy. Please try again."
    }

    override fun discover() {
        // Manual search must explain why it cannot start (rather than silently
        // doing nothing when the phone has not signed into an account).
        if (host || DesktopRemote.scanning.value) return
        val currentScope = scope
        val expected = account
        if (currentScope == null || expected == null) {
            DesktopRemote.status.value = "Sign in to the same account and server on your phone and PC before searching."
            return
        }
        DesktopRemote.scanning.value = true
        DesktopRemote.desktops.value = emptyList()
        DesktopRemote.status.value = "Searching the local network for Starlit PCs…"
        currentScope.launch {
            try {
                DatagramSocket().use { socket ->
                    withContext(Dispatchers.Main) {
                        check(scope === currentScope)
                        scanSocket = socket
                    }
                    socket.broadcast = true
                    socket.soTimeout = 300
                    val challenge = randomRemoteBytes().joinToString("") { "%02x".format(it) }
                    val request = "$REMOTE_PROTOCOL $challenge ${discoveryAccountHint(expected, challenge)}".toByteArray()
                    val broadcasts = remoteBroadcastTargets(localInterfaces().mapNotNull { it.broadcast })
                    var sent = false
                    // Retry once for packet loss or a desktop listener starting up,
                    // while retaining the existing three-second bounded scan.
                    repeat(2) { attempt ->
                        broadcasts.forEach { destination ->
                            if (runCatching {
                                socket.send(DatagramPacket(request, request.size, destination, DISCOVERY_PORT))
                            }.isSuccess) sent = true
                        }
                        if (attempt == 0) delay(250)
                    }
                    check(sent) { "No LAN broadcast path available." }
                    val end = System.nanoTime() + 3_000_000_000L
                    while (System.nanoTime() < end && currentCoroutineContext().isActive) {
                        val packet = DatagramPacket(ByteArray(512), 512)
                        try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                        if (!isRemoteLanPeer(packet.address)) continue
                        val fields = String(packet.data, 0, packet.length, Charsets.UTF_8).split('|', limit = 4)
                        if (fields.size != 4 || fields[0] != REMOTE_PROTOCOL || fields[1] != challenge) continue
                        val port = fields[2].toIntOrNull()?.takeIf { it in 1024..65535 } ?: continue
                        val peer = RemoteDesktop(packet.address.hostAddress!!, port, fields[3].take(80))
                        withContext(Dispatchers.Main) {
                            check(scope === currentScope)
                            DesktopRemote.desktops.value = (DesktopRemote.desktops.value + peer).distinctBy { it.address to it.port }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    check(scope === currentScope)
                    DesktopRemote.status.value = if (DesktopRemote.desktops.value.isEmpty())
                        "No desktops found. Open Starlit on your PC, sign in, and allow it through the private-network firewall. Guest Wi-Fi may block discovery." else "Choose your desktop."
                }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (scope === currentScope) DesktopRemote.status.value = "Could not search the local network. Check Wi-Fi and local-network permissions."
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (scope === currentScope) {
                        scanSocket = null
                        DesktopRemote.scanning.value = false
                    }
                }
            }
        }
    }

    private suspend fun listen(expected: RemoteAccount, ownerScope: CoroutineScope) {
        var listener: ServerSocket? = null
        var udpSocket: DatagramSocket? = null
        try {
            val activeListener = ServerSocket(0).also { listener = it; server = it; it.soTimeout = 1000 }
            currentCoroutineContext().ensureActive()
            val udp = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(DISCOVERY_PORT))
                soTimeout = 1000
            }.also { udpSocket = it; discovery = it }
            currentCoroutineContext().ensureActive()
            coroutineScope {
                launch {
                    while (isActive) {
                        val packet = DatagramPacket(ByteArray(256), 256)
                        try { udp.receive(packet) } catch (_: SocketTimeoutException) { continue }
                        if (!isRemoteLanPeer(packet.address)) continue
                        val request = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val fields = request.split(' ', limit = 3)
                        if (fields.size != 3 || fields[0] != REMOTE_PROTOCOL) continue
                        val challenge = fields[1]
                        if (!challenge.matches(Regex("[0-9a-f]{64}")) || fields[2] != discoveryAccountHint(expected, challenge)) continue
                        val label = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Starlit desktop").replace('|', ' ').take(80)
                        val response = "$REMOTE_PROTOCOL|$challenge|${activeListener.localPort}|$label".toByteArray()
                        udp.send(DatagramPacket(response, response.size, packet.address, packet.port))
                    }
                }
                var lastAttempt = 0L
                while (isActive) {
                    val socket = try { activeListener.accept() } catch (_: SocketTimeoutException) { continue }
                    var registered = false
                    try {
                        val lanPeer = isRemoteLanPeer(socket.inetAddress)
                        withContext(Dispatchers.Main) {
                            val now = System.nanoTime()
                            // Registration and disconnect share Main: an old listener cannot own a new account's session.
                            if (scope === ownerScope && lanPeer && sockets.isEmpty() && now - lastAttempt >= 20_000_000_000L) {
                                lastAttempt = now
                                sockets.add(socket)
                                registered = true
                                val epoch = sessionEpoch.incrementAndGet()
                                connection = ownerScope.launch { session(socket, expected, true, epoch) }
                            }
                        }
                    } finally {
                        if (!registered) runCatching { socket.close() }
                    }
                }
            }
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) {
            withContext(NonCancellable + Dispatchers.Main) {
                if (scope === ownerScope) DesktopRemote.status.value = "Desktop remote could not listen on the LAN. Check your firewall or another Starlit instance."
            }
        } finally {
            runCatching { listener?.close() }
            runCatching { udpSocket?.close() }
        }
    }

    override fun connect(desktop: RemoteDesktop) {
        val currentScope = scope ?: return
        val expected = account ?: return
        disconnect()
        val epoch = sessionEpoch.get()
        connection = currentScope.launch {
            var ownedSocket: Socket? = null
            try {
                val address = InetAddress.getByName(desktop.address)
                require(isRemoteLanPeer(address)) { "Desktop must be on the same local network." }
                val socket = Socket().also { ownedSocket = it }
                withContext(Dispatchers.Main) {
                    check(scope === currentScope && sessionEpoch.get() == epoch)
                    sockets.add(socket)
                }
                socket.connect(InetSocketAddress(address, desktop.port), 5_000)
                session(socket, expected, false, epoch)
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (sessionEpoch.get() == epoch) DesktopRemote.status.value = "Could not connect. Check the desktop and its private-network firewall."
                }
            } finally {
                ownedSocket?.let { socket -> runCatching { socket.close() }; sockets.remove(socket) }
            }
        }
    }

    private suspend fun session(socket: Socket, expected: RemoteAccount, serverSide: Boolean, epoch: Long) {
        try {
            socket.use {
                currentCoroutineContext().ensureActive()
                socket.soTimeout = PAIRING_TIMEOUT.toInt()
                socket.tcpNoDelay = true
                val pairing = openRemoteChannel(socket, serverSide)
                val channel = pairing.channel
                currentCoroutineContext().ensureActive()
                check(sessionEpoch.get() == epoch)
                val approval = CompletableDeferred<Unit>()
                publish(epoch) {
                    confirmation = approval
                    DesktopRemote.pairing.value = RemotePairing(pairing.code, socket.inetAddress.hostAddress.orEmpty())
                    DesktopRemote.status.value = "Compare the code on both devices, then confirm on both."
                }
                val peerToken = authorizeRemoteChannel(
                    channel = channel,
                    expected = expected,
                    host = serverSide,
                    approve = { withTimeout(PAIRING_TIMEOUT) { approval.await() } },
                    currentToken = { currentToken(expected) },
                    verifyToken = { verifyAccount(expected, it) },
                )
                pairedLoop(channel, socket, expected, peerToken, epoch)
            }
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) {
            withContext(NonCancellable + Dispatchers.Main) {
                if (sessionEpoch.get() == epoch) DesktopRemote.status.value = "Remote disconnected. Pair again; both devices must use the same active account and server."
            }
        } finally {
            runCatching { socket.close() }
            sockets.remove(socket)
            withContext(NonCancellable + Dispatchers.Main) {
                if (sessionEpoch.get() == epoch) {
                    confirmation = null
                    DesktopRemote.pairing.value = null
                    DesktopRemote.connected.value = false
                }
            }
        }
    }

    private suspend fun pairedLoop(channel: RemoteCipherChannel, socket: Socket, expected: RemoteAccount, peerToken: String?, epoch: Long) {
        currentCoroutineContext().ensureActive()
        check(sessionEpoch.get() == epoch)
        publish(epoch) {
            DesktopRemote.pairing.value = null
            DesktopRemote.connected.value = true
            DesktopRemote.status.value = "Connected to ${socket.inetAddress.hostAddress}"
        }
        socket.soTimeout = 15_000
        var lastVerification = System.nanoTime()
        val peerAuthorization = peerToken?.let { RemotePeerAuthorization(it) { token -> verifyAccount(expected, token) } }
        while (currentCoroutineContext().isActive && sessionEpoch.get() == epoch) {
            val currentToken = currentToken(expected) // Fail closed immediately on local logout/account/server change.
            if (peerToken != null) {
                val request = wireJson.decodeFromString<RemoteRequest>(channel.receive())
                peerAuthorization!!.accept(request.accessToken)
                val command = request.command
                if (System.nanoTime() - lastVerification > 60_000_000_000L) {
                    verifyAccount(expected, currentToken)
                    lastVerification = System.nanoTime()
                }
                val snapshot = withContext(Dispatchers.Main) {
                    check(sessionEpoch.get() == epoch)
                    currentRemoteAccessToken(expected)
                    if (command.type != "state" && command.playbackId == DesktopRemote.playback.playbackId && command.value.isFinite()) {
                        DesktopRemote.dispatch?.invoke(command)
                    }
                    DesktopRemote.playback
                }
                channel.send(snapshot.toRemoteWireJson())
                delay(50) // Bound even an authenticated client's request rate.
            } else {
                val command = commands.tryReceive().getOrNull() ?: RemoteCommand()
                channel.send(wireJson.encodeToString(RemoteRequest(command, currentToken)))
                val snapshot = wireJson.decodeFromString<RemotePlayback>(channel.receive())
                publish(epoch) { DesktopRemote.remotePlayback.value = snapshot }
                delay(300)
            }
        }
    }

    private suspend fun currentToken(expected: RemoteAccount): String = withContext(Dispatchers.Main) { currentRemoteAccessToken(expected) }

    private suspend fun publish(epoch: Long, block: () -> Unit) = withContext(Dispatchers.Main) {
        check(sessionEpoch.get() == epoch)
        block()
    }

    private fun verifyAccount(expected: RemoteAccount, token: String) {
        require(token.length in 20..16_384)
        val connection = URL("${expected.server}/auth/v1/user").openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("apikey", expected.publishableKey)
            require(connection.responseCode == 200) { "Account verification failed" }
            val body = connection.inputStream.use { stream ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (bytes.size() <= 65_536) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, 65_537 - bytes.size()))
                    if (count < 0) break
                    bytes.write(buffer, 0, count)
                }
                bytes.toByteArray()
            }
            require(body.size <= 65_536)
            require(wireJson.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject["id"]?.jsonPrimitive?.content == expected.userId) {
                "Different account"
            }
        } finally { connection.disconnect() }
    }
}

package com.nuvio.app.features.remote

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
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
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.profiles.ProfileRepository

private const val DISCOVERY_PORT = 47788
internal const val REMOTE_LAN_PORT = 47789
private const val PAIRING_TIMEOUT = 90_000L
private val TCP_PROBE_MARKER = "STRP".toByteArray(Charsets.US_ASCII)
private val wireJson = Json { ignoreUnknownKeys = true }

// A nonce-bound discovery filter, never an authentication proof; account verification follows pairing.
private fun discoveryAccountHint(account: RemoteAccount, nonce: String): String =
    remoteHash("${account.server}\u0000${account.userId}".toByteArray(Charsets.UTF_8), nonce.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** TCP probes authenticate discovery without opening a pairing session. */
internal fun remoteTcpProbe(account: RemoteAccount, nonce: String): ByteArray =
    TCP_PROBE_MARKER + nonce.toByteArray(Charsets.US_ASCII) +
        discoveryAccountHint(account, nonce).toByteArray(Charsets.US_ASCII)

internal fun validRemoteTcpProbe(account: RemoteAccount, bytes: ByteArray): Boolean {
    if (bytes.size != 132 || !bytes.copyOfRange(0, 4).contentEquals(TCP_PROBE_MARKER)) return false
    val nonce = bytes.copyOfRange(4, 68).toString(Charsets.US_ASCII)
    if (!nonce.matches(Regex("[0-9a-f]{64}"))) return false
    return bytes.copyOfRange(68, 132).toString(Charsets.US_ASCII) == discoveryAccountHint(account, nonce)
}

internal fun sameRemoteSubnet(peer: ByteArray, local: ByteArray, prefix: Int): Boolean {
    if (peer.size != 4 || local.size != 4 || prefix !in 1..32) return false
    return (0 until prefix).all { bit ->
        val mask = 1 shl (7 - bit % 8)
        (peer[bit / 8].toInt() and mask) == (local[bit / 8].toInt() and mask)
    }
}

/** Parse only numeric IPv4 literals; DNS names/URLs must never initiate LAN pairing. */
internal fun strictRemoteIpv4(value: String): Inet4Address? {
    val parts = value.trim().split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 ||
            it.any { digit -> digit !in '0'..'9' } }) return null
    val octets = parts.map { it.toIntOrNull()?.takeIf { octet -> octet in 0..255 } ?: return null }
    return InetAddress.getByAddress(octets.map(Int::toByte).toByteArray()) as? Inet4Address
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

/** Never sweep arbitrary routes: at most one /24 per local private interface. */
internal fun remoteSubnetCandidates(local: ByteArray, prefix: Int): List<Inet4Address> {
    if (local.size != 4 || prefix !in 16..30) return emptyList()
    val localIp = local.fold(0L) { value, part -> (value shl 8) or (part.toLong() and 0xff) }
    val effectivePrefix = maxOf(prefix, 24)
    val mask = (0xffffffffL shl (32 - effectivePrefix)) and 0xffffffffL
    val network = localIp and mask
    val size = 1 shl (32 - effectivePrefix)
    return (1 until size - 1).mapNotNull { host ->
        val value = network or host.toLong()
        if (value == localIp) null else
            InetAddress.getByAddress(ByteArray(4) { bit -> (value ushr (24 - bit * 8)).toByte() }) as Inet4Address
    }
}

/** Standard Wake-on-LAN magic packet, prepared only for a validated unicast NIC MAC. */
internal fun wakeMagicPacket(mac: String): ByteArray? {
    val canonical = normalizedWakeMac(mac) ?: return null
    val bytes = canonical.split(':').map { it.toInt(16).toByte() }
    return ByteArray(102).also { packet ->
        packet.fill(0xff.toByte(), 0, 6)
        repeat(16) { round -> bytes.forEachIndexed { i, byte -> packet[6 + round * 6 + i] = byte } }
    }
}

/** No HTTP listener: all LAN payloads after the committed handshake use AES-GCM. */
internal class LanDesktopRemote(
    private val bluetoothClient: RemoteBluetoothClient? = null,
    private val bluetoothHost: RemoteBluetoothHost? = null,
) : DesktopRemoteTransport {
    override val supported = true
    override val bluetoothSupported get() = bluetoothClient?.supported == true
    override fun localAddresses(): List<String> =
        runCatching { localInterfaces().mapNotNull { it.address.hostAddress }.distinct().sorted() }
            .getOrDefault(emptyList())
    @Volatile private var scope: CoroutineScope? = null
    private var account: RemoteAccount? = null
    private var host = false
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var discovery: DatagramSocket? = null
    @Volatile private var scanSocket: DatagramSocket? = null
    @Volatile private var bluetoothClose: (() -> Unit)? = null
    @Volatile private var connection: Job? = null
    @Volatile private var selectedDesktop: RemoteDesktop? = null
    @Volatile private var confirmation: CompletableDeferred<Unit>? = null
    private var commands = Channel<RemoteCommand>(32)
    private val sessionEpoch = AtomicLong()
    private val lastWakeNanos = AtomicLong(0L)
    private val lastHandoffAtNanos = AtomicLong(0L)
    private var bluetoothListener: ServerSocket? = null
    private var lastHostAttemptNanos = 0L

    override fun start(account: RemoteAccount, host: Boolean) {
        stop()
        this.account = account
        this.host = host
        val currentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = currentScope
        if (host) currentScope.launch { listen(account, currentScope) }
    }

    override fun stop() {
        DesktopRemote.hostReady.value = false
        DesktopRemote.bluetoothHostReady.value = false
        scope?.cancel()
        scope = null
        account = null
        disconnect()
        runCatching { server?.close() }
        runCatching { discovery?.close() }
        runCatching { scanSocket?.close() }
        runCatching { bluetoothListener?.close() }
        runCatching { bluetoothHost?.stop() }
        runCatching { bluetoothClient?.stop() }
        server = null
        discovery = null
        scanSocket = null
        bluetoothListener = null
        DesktopRemote.desktops.value = emptyList()
        DesktopRemote.bluetoothDesktops.value = emptyList()
        DesktopRemote.scanning.value = false
    }

    override fun disconnect() {
        sessionEpoch.incrementAndGet()
        selectedDesktop = null
        DesktopRemote.pendingHandoff.value = null
        connection?.cancel()
        connection = null
        confirmation?.cancel()
        confirmation = null
        runCatching { bluetoothClose?.invoke() }
        bluetoothClose = null
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        commands.close()
        commands = Channel(32)
        DesktopRemote.pairing.value = null
        DesktopRemote.connected.value = false
        DesktopRemote.remotePlayback.value = RemotePlayback()
    }

    override fun confirmPairing() { confirmation?.complete(Unit) }

    override fun discoverBluetooth() {
        val currentScope = scope
        val activeClient = bluetoothClient
        if (currentScope == null || account == null) {
            DesktopRemote.status.value = "Sign in to the same account and server on your phone and PC first."
            return
        }
        if (activeClient?.supported != true) {
            DesktopRemote.status.value = "Bluetooth control is not available on this device."
            return
        }
        DesktopRemote.bluetoothDesktops.value = emptyList()
        DesktopRemote.status.value = "Searching nearby Bluetooth PCs…"
        activeClient.discover(
            onFound = { desktop ->
                currentScope.launch(Dispatchers.Main) {
                    if (scope === currentScope && !host) {
                        DesktopRemote.bluetoothDesktops.value =
                            (DesktopRemote.bluetoothDesktops.value.filterNot { it.address == desktop.address } + desktop)
                    }
                }
            },
            onFailure = { message ->
                currentScope.launch(Dispatchers.Main) {
                    if (scope === currentScope && DesktopRemote.bluetoothDesktops.value.isEmpty()) {
                        DesktopRemote.status.value = message
                    }
                }
            },
        )
    }

    override fun wake(address: String, mac: String) {
        val currentScope = scope ?: return
        if (host) return
        // A wake request must name a user-configured MAC on a previously paired
        // PC. Wake addresses are hints, never an authorization shortcut.
        if (DesktopRemote.remembered.value.none { it.address == address && it.wakeMac == normalizedWakeMac(mac) }) {
            DesktopRemote.status.value = "Save the PC's network adapter MAC address first."
            return
        }
        val target = strictRemoteIpv4(address)
        val bytes = wakeMagicPacket(mac)
        if (target == null || !isRemoteLanPeer(target) || bytes == null) {
            DesktopRemote.status.value = "Wake-on-LAN is only available for a saved PC on this local network."
            return
        }
        val now = System.nanoTime()
        val previous = lastWakeNanos.get()
        if (previous != 0L && now - previous in 0 until 10_000_000_000L) {
            DesktopRemote.status.value = "Wake request already sent. Wait before trying again."
            return
        }
        lastWakeNanos.set(now)
        currentScope.launch {
            try {
                val broadcasts = localInterfaces().filter {
                    sameRemoteSubnet(target.address, it.address.address, it.networkPrefixLength.toInt())
                }.mapNotNull { it.broadcast }.distinct()
                check(broadcasts.isNotEmpty()) { "No usable local interface broadcast address." }
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    currentCoroutineContext().ensureActive()
                    broadcasts.forEach { destination ->
                        socket.send(DatagramPacket(bytes, bytes.size, destination, 9))
                    }
                }
                withContext(Dispatchers.Main) {
                    if (scope === currentScope) DesktopRemote.status.value =
                        "Wake-on-LAN packet sent. Check your PC and search the LAN again; waking is not guaranteed."
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (scope === currentScope) DesktopRemote.status.value =
                        "Could not send Wake-on-LAN. Check Wi-Fi, adapter MAC and router broadcast support."
                }
            }
        }
    }
    override fun send(command: RemoteCommand) {
        if (!commands.trySend(command).isSuccess) DesktopRemote.status.value = "Remote is busy. Please try again."
    }

    override fun discover(localIp: String?, deepScan: Boolean) {
        // Manual search must explain why it cannot start (rather than silently
        // doing nothing when the phone has not signed into an account).
        if (host || DesktopRemote.scanning.value) return
        val currentScope = scope
        val expected = account
        if (currentScope == null || expected == null) {
            DesktopRemote.status.value = "Sign in to the same account and server on your phone and PC before searching."
            return
        }
        val targetAddress = if (localIp == null) null else {
            val address = strictRemoteIpv4(localIp)
            if (address == null || !isRemoteLanPeer(address)) {
                DesktopRemote.status.value = "Enter a private IPv4 address on your current local subnet. No public IPs, hostnames or port forwarding."
                return
            }
            address
        }
        DesktopRemote.scanning.value = true
        DesktopRemote.desktops.value = emptyList()
        DesktopRemote.status.value = if (targetAddress != null)
            "Checking Starlit on ${targetAddress.hostAddress}…" else "Searching the local network for Starlit PCs…"
        currentScope.launch {
            try {
                DatagramSocket().use { socket ->
                    withContext(Dispatchers.Main) {
                        check(scope === currentScope)
                        scanSocket = socket
                    }
                    socket.broadcast = targetAddress == null
                    socket.soTimeout = 300
                    val challenge = randomRemoteBytes().joinToString("") { "%02x".format(it) }
                    val request = "$REMOTE_PROTOCOL $challenge ${discoveryAccountHint(expected, challenge)}".toByteArray()
                    val broadcasts = if (targetAddress == null)
                        remoteBroadcastTargets(localInterfaces().mapNotNull { it.broadcast })
                    else listOf(targetAddress)
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
                    // Manual IP and saved devices must not depend on the UDP
                    // response, which Windows firewalls commonly block.
                    val directTargets = if (targetAddress != null) listOf(targetAddress)
                        else DesktopRemote.remembered.value.mapNotNull { strictRemoteIpv4(it.address) }
                            .filter(::isRemoteLanPeer)
                    probeRemoteTargets(directTargets, currentScope, expected)
                    val end = System.nanoTime() + 2_000_000_000L
                    while (System.nanoTime() < end && currentCoroutineContext().isActive) {
                        val packet = DatagramPacket(ByteArray(512), 512)
                        try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                        if (!isRemoteLanPeer(packet.address) ||
                            (targetAddress != null && packet.address != targetAddress)) continue
                        val fields = String(packet.data, 0, packet.length, Charsets.UTF_8).split('|', limit = 4)
                        if (fields.size != 4 || fields[0] != REMOTE_PROTOCOL || fields[1] != challenge) continue
                        val port = fields[2].toIntOrNull()?.takeIf { it in 1024..65535 } ?: continue
                        val peer = RemoteDesktop(packet.address.hostAddress!!, port, fields[3].take(80))
                        withContext(Dispatchers.Main) {
                            check(scope === currentScope)
                            DesktopRemote.desktops.value = DesktopRemote.desktops.value.filterNot { it.address == peer.address } + peer
                        }
                    }
                    if (deepScan && targetAddress == null) {
                        withContext(Dispatchers.Main) {
                            if (scope === currentScope) DesktopRemote.status.value = "Checking local PCs directly…"
                        }
                        val candidates = localInterfaces().take(2)
                            .flatMap { remoteSubnetCandidates(it.address.address, it.networkPrefixLength.toInt()) }
                            .distinctBy { it.hostAddress }
                            .filter(::isRemoteLanPeer)
                        probeRemoteTargets(candidates, currentScope, expected)
                    }
                }
                withContext(Dispatchers.Main) {
                    check(scope === currentScope)
                    DesktopRemote.status.value = if (DesktopRemote.desktops.value.isEmpty()) {
                        if (targetAddress == null)
                            "No desktops found. Open Starlit on your PC, sign in, and allow TCP $REMOTE_LAN_PORT and UDP $DISCOVERY_PORT through its private-network firewall. Guest Wi-Fi may block discovery."
                        else "Could not reach Starlit at ${targetAddress.hostAddress}:$REMOTE_LAN_PORT. Check the PC's private-network firewall."
                    } else "Choose your desktop."
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

    private suspend fun probeRemoteTargets(addresses: List<Inet4Address>, ownerScope: CoroutineScope, expected: RemoteAccount) {
        // A bounded number of short TCP probes avoids flooding the network
        // or spawning hundreds of simultaneously blocked sockets.
        val probeDispatcher = Dispatchers.IO.limitedParallelism(16)
        coroutineScope {
            addresses.map { address ->
                async(probeDispatcher) {
                    currentCoroutineContext().ensureActive()
                    val reachable = runCatching {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(address, REMOTE_LAN_PORT), 350)
                            socket.soTimeout = 700
                            val nonce = randomRemoteBytes().joinToString("") { "%02x".format(it) }
                            socket.getOutputStream().write(remoteTcpProbe(expected, nonce))
                            socket.getOutputStream().flush()
                            socket.getInputStream().read() == 1
                        }
                    }.getOrDefault(false)
                    if (reachable) withContext(Dispatchers.Main) {
                        check(scope === ownerScope)
                        val addressText = address.hostAddress.orEmpty()
                        if (DesktopRemote.desktops.value.none { it.address == addressText }) {
                            val knownName = DesktopRemote.remembered.value.firstOrNull { it.address == addressText }?.name
                            DesktopRemote.desktops.value += RemoteDesktop(addressText, REMOTE_LAN_PORT, knownName ?: "Starlit PC")
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun listen(expected: RemoteAccount, ownerScope: CoroutineScope) {
        var listener: ServerSocket? = null
        var udpSocket: DatagramSocket? = null
        var localBluetoothListener: ServerSocket? = null
        try {
            val activeListener = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(REMOTE_LAN_PORT))
                soTimeout = 1000
            }.also { listener = it; server = it }
            currentCoroutineContext().ensureActive()
            val udp = runCatching { DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(DISCOVERY_PORT))
                soTimeout = 1000
            } }.getOrNull()?.also { udpSocket = it; discovery = it }
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.Main) {
                check(scope === ownerScope)
                DesktopRemote.hostReady.value = true
                DesktopRemote.status.value = "Ready to pair with your phone on this local network."
            }
            coroutineScope {
                if (udp != null) launch {
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
                if (bluetoothHost != null) {
                    localBluetoothListener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).also {
                        it.soTimeout = 1000
                        bluetoothListener = it
                    }
                    val enabled = runCatching { bluetoothHost.start(localBluetoothListener!!.localPort) }.getOrDefault(false)
                    withContext(Dispatchers.Main) {
                        if (scope === ownerScope) DesktopRemote.bluetoothHostReady.value = enabled
                    }
                    if (enabled) launch {
                        val btListener = localBluetoothListener!!
                        while (isActive) {
                            val socket = try { btListener.accept() } catch (_: SocketTimeoutException) { continue }
                            val registered = registerHostSocket(socket, expected, ownerScope)
                            if (!registered) runCatching { socket.close() }
                        }
                    }
                }
                while (isActive) {
                    val socket = try { activeListener.accept() } catch (_: SocketTimeoutException) { continue }
                    var registered = false
                    try {
                        val lanPeer = isRemoteLanPeer(socket.inetAddress)
                        if (!lanPeer) continue
                        // New clients send a short authenticated discovery
                        // probe. Older clients remain server-first: an idle
                        // socket becomes a normal encrypted pairing session.
                        socket.soTimeout = 300
                        val first = try { socket.getInputStream().read() }
                            catch (_: SocketTimeoutException) { -2 }
                        if (first == -1) continue
                        if (first >= 0) {
                            if (first == TCP_PROBE_MARKER[0].toInt()) {
                                val remaining = ByteArray(131)
                                val valid = runCatching {
                                    socket.soTimeout = 700
                                    DataInputStream(socket.getInputStream()).readFully(remaining)
                                    validRemoteTcpProbe(expected, byteArrayOf(first.toByte()) + remaining)
                                }.getOrDefault(false)
                                if (valid) socket.getOutputStream().write(1)
                            }
                            continue
                        }
                        registered = registerHostSocket(socket, expected, ownerScope)
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
            runCatching { localBluetoothListener?.close() }
            // An obsolete listener must not stop a newly restarted account's Bluetooth host.
            if (scope === ownerScope) runCatching { bluetoothHost?.stop() }
            withContext(NonCancellable + Dispatchers.Main) {
                if (scope === ownerScope) {
                    DesktopRemote.hostReady.value = false
                    DesktopRemote.bluetoothHostReady.value = false
                }
            }
        }
    }

    private suspend fun registerHostSocket(socket: Socket, expected: RemoteAccount, ownerScope: CoroutineScope): Boolean {
        var registered = false
        withContext(Dispatchers.Main) {
            val now = System.nanoTime()
            // LAN and RFCOMM share one active, explicitly authorized session.
            if (scope === ownerScope && sockets.isEmpty() &&
                (lastHostAttemptNanos == 0L || now - lastHostAttemptNanos >= 20_000_000_000L)) {
                lastHostAttemptNanos = now
                sockets.add(socket)
                registered = true
                val epoch = sessionEpoch.incrementAndGet()
                connection = ownerScope.launch { session(socket, expected, true, epoch) }
            }
        }
        return registered
    }

    override fun connect(desktop: RemoteDesktop) {
        val currentScope = scope ?: return
        val expected = account ?: return
        if (desktop.bluetooth) {
            connectBluetooth(desktop, currentScope, expected)
            return
        }
        disconnect()
        selectedDesktop = desktop
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

    private fun connectBluetooth(desktop: RemoteDesktop, ownerScope: CoroutineScope, expected: RemoteAccount) {
        val client = bluetoothClient ?: return
        disconnect()
        selectedDesktop = desktop
        val epoch = sessionEpoch.get()
        connection = ownerScope.launch {
            try {
                val bt = client.connect(desktop.address)
                bluetoothClose = bt.close
                check(scope === ownerScope && sessionEpoch.get() == epoch)
                session(
                    input = bt.input,
                    output = bt.output,
                    peer = bt.peer,
                    configureTimeout = {},
                    closeTransport = bt.close,
                    expected = expected,
                    serverSide = false,
                    epoch = epoch,
                )
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (sessionEpoch.get() == epoch) DesktopRemote.status.value =
                        "Bluetooth connection failed. Pair with the PC in device settings and keep Bluetooth enabled on both."
                }
            } finally {
                runCatching { bluetoothClose?.invoke() }
                bluetoothClose = null
            }
        }
    }

    private suspend fun session(socket: Socket, expected: RemoteAccount, serverSide: Boolean, epoch: Long) {
        socket.tcpNoDelay = true
        try {
            session(
                input = socket.getInputStream(),
                output = socket.getOutputStream(),
                peer = socket.inetAddress.hostAddress.orEmpty(),
                configureTimeout = { socket.soTimeout = it },
                closeTransport = { socket.close() },
                expected = expected,
                serverSide = serverSide,
                epoch = epoch,
            )
        } finally {
            sockets.remove(socket)
        }
    }

    private suspend fun session(
        input: InputStream,
        output: OutputStream,
        peer: String,
        configureTimeout: (Int) -> Unit,
        closeTransport: () -> Unit,
        expected: RemoteAccount,
        serverSide: Boolean,
        epoch: Long,
    ) {
        try {
            try {
                currentCoroutineContext().ensureActive()
                configureTimeout(PAIRING_TIMEOUT.toInt())
                val pairing = openRemoteChannel(input, output, serverSide)
                val channel = pairing.channel
                currentCoroutineContext().ensureActive()
                check(sessionEpoch.get() == epoch)
                val approval = CompletableDeferred<Unit>()
                publish(epoch) {
                    confirmation = approval
                    DesktopRemote.pairing.value = RemotePairing(pairing.code, peer)
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
                pairedLoop(channel, peer, configureTimeout, expected, peerToken, epoch)
            } finally {
                runCatching { closeTransport() }
            }
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) {
            withContext(NonCancellable + Dispatchers.Main) {
                if (sessionEpoch.get() == epoch) DesktopRemote.status.value = "Remote disconnected. Pair again; both devices must use the same active account and server."
            }
        } finally {
            runCatching { closeTransport() }
            withContext(NonCancellable + Dispatchers.Main) {
                if (sessionEpoch.get() == epoch) {
                    confirmation = null
                    DesktopRemote.pairing.value = null
                    DesktopRemote.connected.value = false
                    DesktopRemote.pendingHandoff.value = null
                }
            }
        }
    }

    private suspend fun pairedLoop(
        channel: RemoteCipherChannel,
        peer: String,
        configureTimeout: (Int) -> Unit,
        expected: RemoteAccount,
        peerToken: String?,
        epoch: Long,
    ) {
        currentCoroutineContext().ensureActive()
        check(sessionEpoch.get() == epoch)
        if (peerToken != null) {
            // Load the desktop's active profile queue only after encrypted,
            // two-device approved account verification, not at app startup.
            withContext(Dispatchers.Main) {
                check(sessionEpoch.get() == epoch)
                currentRemoteAccessToken(expected)
                DownloadsRepository.ensureLoaded()
            }
        }
        publish(epoch) {
            DesktopRemote.pairing.value = null
            DesktopRemote.connected.value = true
            DesktopRemote.status.value = "Connected to $peer"
            if (peerToken == null) {
                // A device record is only a discovery shortcut. Pairing, both
                // code confirmations and account verification already completed.
                selectedDesktop?.takeUnless { it.bluetooth }?.let { desktop ->
                    runCatching {
                        DesktopRemote.pairedWith(expected, desktop, System.currentTimeMillis())
                    }
                }
            }
        }
        configureTimeout(15_000)
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
                    if (command.type == "handoffRequest") {
                        val intent = command.acceptableHandoff(
                            ProfileRepository.activeProfileId, DesktopRemote.playback.playbackId,
                        )
                        if (intent != null && DesktopRemote.pendingHandoff.value == null) {
                            val now = System.nanoTime()
                            val previous = lastHandoffAtNanos.get()
                            // Prevent an otherwise authorized phone from flooding
                            // the desktop with navigation prompts.
                            if (previous == 0L || now - previous !in 0 until 20_000_000_000L) {
                                lastHandoffAtNanos.set(now)
                                DesktopRemote.pendingHandoff.value = intent
                            }
                        }
                    } else if (command.type == "downloadPause" || command.type == "downloadResume") {
                        val profileId = ProfileRepository.activeProfileId
                        val task = command.allowedDownloadTask(
                            DownloadsRepository.uiState.value, profileId, DesktopRemote.playback.playbackId,
                        )
                        if (task != null) {
                            if (command.type == "downloadPause") DownloadsRepository.pauseDownload(task.id)
                            else DownloadsRepository.resumeDownload(task.id)
                        }
                    } else if (command.type != "state" &&
                        command.playbackId == DesktopRemote.playback.playbackId && command.value.isFinite()) {
                        DesktopRemote.dispatch?.invoke(command)
                    }
                    val profileId = ProfileRepository.activeProfileId
                    DesktopRemote.playback.copy(
                        downloadProfileId = profileId,
                        downloads = if (command.profileId == profileId)
                            remoteDownloadSnapshot(DownloadsRepository.uiState.value, profileId)
                        else emptyList(),
                    )
                }
                channel.send(snapshot.toRemoteWireJson())
                delay(50) // Bound even an authenticated client's request rate.
            } else {
                val command = commands.tryReceive().getOrNull() ?: RemoteCommand(
                    profileId = ProfileRepository.activeProfileId,
                )
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

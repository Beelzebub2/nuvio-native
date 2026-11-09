package com.nuvio.app.features.remote

import java.io.*
import java.net.BindException
import java.net.ConnectException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlin.test.*
import org.junit.Assume.assumeTrue

class DesktopRemoteProtocolTest {
    @Test
    fun occupiedRemotePortHasActionableMessageAndCanBeReboundAfterRelease() {
        val blocker = ServerSocket(0)
        val port = blocker.localPort
        try {
            val failure = assertFailsWith<BindException> {
                bindRemoteTcpListener(port).close()
            }
            assertContains(remoteHostFailureMessage(failure, port), "Close another Starlit instance")
            assertContains(remoteHostFailureMessage(failure, port), "$port")
        } finally {
            blocker.close()
        }
        bindRemoteTcpListener(port).use { listener ->
            assertEquals(port, listener.localPort)
        }
    }

    @Test
    fun securityFailuresAreNotMislabelledAsFirewallRules() {
        assertContains(remoteHostFailureMessage(SecurityException("denied")), "denied access")
        assertContains(remoteHostFailureMessage(IllegalStateException("other")), "retry automatically")
    }

    @Test
    fun connectionErrorsNameTheFailedEndpointWithoutGuessingTheCause() {
        val address = "192.168.1.50"
        assertContains(remoteConnectionFailureMessage(ConnectException(), address, 47789), "was refused")
        val timeout = remoteConnectionFailureMessage(SocketTimeoutException(), address, 47789)
        assertContains(timeout, "$address:47789")
        assertContains(timeout, "guest Wi-Fi, a VPN, or a firewall may")
        assertFalse(timeout.contains("firewall is blocking"))
        assertContains(remoteConnectionFailureMessage(SecurityException(), address, 47789), "network permissions")
        assertContains(remoteConnectionFailureMessage(IllegalArgumentException(), address, 47789), "private IPv4")
    }

    private fun localTestAddress(): String {
        val addresses = LanDesktopRemote().localAddresses()
        assumeTrue("A private local interface is needed to exercise the production peer restriction", addresses.isNotEmpty())
        return addresses.first()
    }

    @Test
    fun desktopHostRecoversAfterTemporaryPortConflictWithoutSigningInAgain() = runBlocking {
        val occupied = ServerSocket(0)
        val port = occupied.localPort
        val udpPort = DatagramSocket(0).use { it.localPort }
        val host = LanDesktopRemote(listenPort = port, discoveryPort = udpPort, uiDispatcher = Dispatchers.Default)
        try {
            host.start(RemoteAccount("account", "https://example.org", "publishable"), host = true)
            withTimeout(5_000) {
                // Other remote tests share DesktopRemote's status flows. Only
                // accept a failure belonging to this listener and its port.
                while (!DesktopRemote.hostFailure.value ||
                    !DesktopRemote.status.value.contains("TCP port $port")) delay(25)
            }
            assertFalse(DesktopRemote.hostReady.value)
            assertContains(DesktopRemote.status.value, "TCP port $port")
            occupied.close()
            withTimeout(10_000) {
                while (!DesktopRemote.hostReady.value) delay(25)
            }
            assertTrue(DesktopRemote.discoveryReady.value)
            assertFalse(DesktopRemote.hostFailure.value)
        } finally {
            host.stop()
            occupied.close()
        }
    }

    @Test
    fun udpDiscoveryFailureDoesNotPreventQrOrManualIpPairing() = runBlocking {
        val address = localTestAddress()
        val tcpPort = ServerSocket(0).use { it.localPort }
        val occupiedUdp = DatagramSocket(0)
        val host = LanDesktopRemote(listenPort = tcpPort, discoveryPort = occupiedUdp.localPort, uiDispatcher = Dispatchers.Default)
        try {
            host.start(RemoteAccount("account", "https://example.org", "publishable"), host = true)
            withTimeout(5_000) {
                while (!DesktopRemote.hostReady.value) delay(25)
            }
            assertFalse(DesktopRemote.discoveryReady.value)
            assertContains(DesktopRemote.status.value, "QR")
            // Exercise the actual host, its LAN guard and the committed encrypted handshake.
            // No approval or account token is supplied, so no commands can be accepted.
            withContext(Dispatchers.IO) {
                Socket(address, tcpPort).use { phone ->
                    phone.soTimeout = 5_000
                    val pairing = openRemoteChannel(phone, host = false)
                    // New peers negotiate LAN trust before the desktop shows the
                    // first-time code. Reply as an untrusted phone to exercise
                    // the real two-device approval path without credentials.
                    assertTrue(pairing.supportsLanTrustedReconnect)
                    val trust = resumeLanTrust(
                        pairing, RemoteAccount("account", "https://example.org", "publishable"),
                        host = false, store = null,
                    )
                    assertFalse(trust.trusted)
                    withTimeout(5_000) {
                        while (DesktopRemote.pairing.value == null) delay(25)
                    }
                    assertEquals(pairing.code, DesktopRemote.pairing.value?.code)
                    assertFalse(DesktopRemote.connected.value)
                }
            }
        } finally {
            host.stop()
            occupiedUdp.close()
        }
    }

    @Test
    fun discoveryWorkerFailureKeepsTheProductionTcpListenerAlive() = runBlocking {
        val address = localTestAddress()
        val tcpPort = ServerSocket(0).use { it.localPort }
        val udp = DatagramSocket(0).apply { soTimeout = 1_000 }
        val account = RemoteAccount("account", "https://example.org", "publishable")
        val host = LanDesktopRemote(listenPort = tcpPort, uiDispatcher = Dispatchers.Default,
            openDiscoverySocket = { udp })
        try {
            host.start(account, host = true)
            withTimeout(5_000) { while (!DesktopRemote.hostReady.value || !DesktopRemote.discoveryReady.value) delay(25) }
            udp.close() // A receive failure after the listener has already started.
            withTimeout(5_000) { while (DesktopRemote.discoveryReady.value) delay(25) }
            assertTrue(DesktopRemote.hostReady.value)
            assertFalse(DesktopRemote.hostFailure.value)
            withContext(Dispatchers.IO) {
                Socket(address, tcpPort).use { phone ->
                    phone.soTimeout = 5_000
                    val nonce = "a".repeat(64)
                    phone.getOutputStream().write(remoteTcpProbe(account, nonce))
                    phone.getOutputStream().flush()
                    assertEquals(1, phone.getInputStream().read())
                }
            }
        } finally {
            host.stop()
            udp.close()
        }
    }

    @Test
    fun manualTcpDiscoveryWorksWhenUdpSocketCreationIsDenied() = runBlocking {
        val address = localTestAddress()
        val account = RemoteAccount("account", "https://example.org", "publishable")
        bindRemoteTcpListener(0).use { listener ->
            val response = async(Dispatchers.IO) {
                listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val probe = ByteArray(132)
                    DataInputStream(socket.getInputStream()).readFully(probe)
                    assertTrue(validRemoteTcpProbe(account, probe))
                    socket.getOutputStream().write(1)
                    socket.getOutputStream().flush()
                }
            }
            val phone = LanDesktopRemote(listenPort = listener.localPort, uiDispatcher = Dispatchers.Default,
                openDiscoverySocket = { throw SocketException("UDP denied") })
            try {
                phone.start(account, host = false)
                phone.discover(localIp = address)
                withTimeout(5_000) { while (DesktopRemote.scanning.value) delay(25) }
                response.await()
                assertEquals(listOf(address), DesktopRemote.desktops.value.map { it.address })
                assertEquals(listener.localPort, DesktopRemote.desktops.value.single().port)
                assertEquals("Choose your desktop.", DesktopRemote.status.value)
            } finally { phone.stop() }
        }
    }

    @Test
    fun controllerOpensEvenWhenNoDesktopsHaveBeenDiscovered() {
        val wasVisible = DesktopRemote.visible.value
        val previousDesktops = DesktopRemote.desktops.value
        val previousStatus = DesktopRemote.status.value
        try {
            DesktopRemote.visible.value = false
            DesktopRemote.desktops.value = emptyList()
            DesktopRemote.open()
            assertTrue(DesktopRemote.visible.value)
            assertTrue(DesktopRemote.desktops.value.isEmpty())
        } finally {
            DesktopRemote.visible.value = wasVisible
            DesktopRemote.desktops.value = previousDesktops
            DesktopRemote.status.value = previousStatus
        }
    }

    @Test
    fun manualSearchWithoutSignedInAccountShowsAnActionableMessage() {
        val previousStatus = DesktopRemote.status.value
        try {
            val freshPhoneTransport = LanDesktopRemote()
            DesktopRemote.status.value = "Not searching"
            freshPhoneTransport.discover()
            assertTrue(DesktopRemote.status.value.contains("Sign in"))
            assertFalse(DesktopRemote.scanning.value)
        } finally {
            DesktopRemote.status.value = previousStatus
        }
    }

    @Test
    fun socketPeersRequireBothApprovalsAndVerifyBothAccountTokensBeforeCommands() = runBlocking {
        withPeers { host, phone ->
            assertEquals(host.code, phone.code)
            val account = RemoteAccount("account-a", "https://accounts.example", "public")
            val hostApproval = CompletableDeferred<Unit>()
            val phoneApproval = CompletableDeferred<Unit>()
            val verified = CopyOnWriteArrayList<String>()
            val phoneTokenRead = AtomicBoolean()
            val hostAuth = async(Dispatchers.IO) {
                authorizeRemoteChannel(host.channel, account, true, { hostApproval.await() }, { "desktop-token" }, { verified += it })
            }
            val phoneAuth = async(Dispatchers.IO) {
                authorizeRemoteChannel(phone.channel, account, false, { phoneApproval.await() }, { phoneTokenRead.set(true); "phone-token" }, { fail("Phone does not validate tokens locally") })
            }
            delay(100)
            assertTrue(verified.isEmpty())
            assertFalse(phoneTokenRead.get())
            hostApproval.complete(Unit)
            delay(100)
            assertFalse(phoneTokenRead.get(), "Phone must approve before exposing its token")
            phoneApproval.complete(Unit)
            assertNull(phoneAuth.await())
            assertEquals("phone-token", hostAuth.await())
            assertEquals(listOf("desktop-token", "phone-token"), verified.toList())
            phone.channel.send("pause")
            assertEquals("pause", host.channel.receive())
            host.channel.send("paused at 12000")
            assertEquals("paused at 12000", phone.channel.receive())
        }
    }

    @Test
    fun differentAccountOrServerIsRejectedBeforePhoneTokenIsRead() = runBlocking {
        for (wrongServer in listOf(false, true)) {
        withPeers { host, phone ->
            val hostAccount = RemoteAccount("account-a", "https://accounts.example", "public")
            val tokenRead = AtomicBoolean()
            val hostAuth = async(Dispatchers.IO) {
                runCatching { authorizeRemoteChannel(host.channel, hostAccount, true, {}, { "desktop-token" }, {}) }
            }
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    val wrongAccount = if (wrongServer) hostAccount.copy(server = "https://different.example") else hostAccount.copy(userId = "account-b")
                    authorizeRemoteChannel(phone.channel, wrongAccount, false, {}, { tokenRead.set(true); "phone-token" }, {})
                }.exceptionOrNull()
            }
            assertIs<IllegalArgumentException>(error)
            assertFalse(tokenRead.get())
            // Host times out/EOF rather than entering a playback loop.
            assertTrue(hostAuth.await().isFailure)
        }
        }
    }

    @Test
    fun rejectedAccountTokenCannotCompleteAuthorization() = runBlocking {
        withPeers { host, phone ->
            val account = RemoteAccount("account-a", "https://accounts.example", "public")
            val hostAuth = async(Dispatchers.IO) {
                runCatching {
                    authorizeRemoteChannel(host.channel, account, true, {}, { "desktop-token" }, {
                        require(it != "revoked-phone-token") { "Server rejected token" }
                    })
                }
            }
            val phoneAuth = async(Dispatchers.IO) {
                runCatching { authorizeRemoteChannel(phone.channel, account, false, {}, { "revoked-phone-token" }, {}) }
            }
            assertIs<IllegalArgumentException>(hostAuth.await().exceptionOrNull())
            assertTrue(phoneAuth.await().isFailure)
        }
    }

    @Test
    fun oversizedUnicodeAndEscapedMetadataIsBoundedBeforeSending() {
        val choices = List(2000) { RemoteChoice("$it-" + "\u0000".repeat(200), "🎞".repeat(150)) }
        val encoded = RemotePlayback(playbackId = "id", episodes = choices, subtitles = choices, sources = choices).toRemoteWireJson()
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= MAX_REMOTE_FRAME / 2)
        assertTrue(encoded.contains("Some choices are hidden"))
    }

    @Test
    fun rejectionAndTimeoutNeverObtainAccountToken() = runBlocking {
        withPeers { host, _ ->
            val account = RemoteAccount("a", "https://accounts.example", "public")
            var tokenReads = 0
            assertFailsWith<IllegalStateException> {
                authorizeRemoteChannel(host.channel, account, true, { error("Codes differ") }, { tokenReads++; "token" }, { fail("Must not verify") })
            }
            assertFailsWith<TimeoutCancellationException> {
                authorizeRemoteChannel(host.channel, account, true, { withTimeout(20) { awaitCancellation() } }, { tokenReads++; "token" }, { fail("Must not verify") })
            }
            assertEquals(0, tokenReads)
        }
    }

    @Test
    fun tokenRefreshIsVerifiedBeforeAdoptionAndRevocationFailsClosed() = runBlocking {
        val verified = mutableListOf<String>()
        val initial = "initial-token-for-the-same-account"
        val refreshed = "refreshed-token-for-the-same-account"
        val wrongAccount = "token-from-a-different-account"
        val peer = RemotePeerAuthorization(initial) {
            verified += it
            require(it != wrongAccount) { "Server returned another user" }
        }
        peer.accept(initial)
        assertTrue(verified.isEmpty())
        peer.accept(refreshed)
        assertEquals(listOf(refreshed), verified)
        peer.accept(refreshed)
        assertEquals(1, verified.size)
        assertFailsWith<IllegalArgumentException> { peer.accept(wrongAccount) }
        assertFailsWith<IllegalArgumentException> { peer.accept(wrongAccount) }
        peer.accept(refreshed, System.nanoTime() + 61_000_000_000)
        assertEquals(refreshed, verified.last())
        assertEquals(4, verified.size)
    }

    @Test
    fun ciphertextTamperingReplayAndReflectionAreRejected() {
        val transcript = randomRemoteBytes()
        val bytes = ByteArrayOutputStream()
        val sender = RemoteCipherChannel(DataInputStream(ByteArrayInputStream(byteArrayOf())), DataOutputStream(bytes), transcript, true)
        sender.send("a command")
        val frame = bytes.toByteArray()
        fun receiver(data: ByteArray, host: Boolean = false) = RemoteCipherChannel(DataInputStream(ByteArrayInputStream(data)), DataOutputStream(ByteArrayOutputStream()), transcript, host)
        val replay = receiver(frame + frame)
        assertEquals("a command", replay.receive())
        assertFailsWith<IllegalArgumentException> { replay.receive() }
        assertFails { receiver(frame.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }).receive() }
        assertFails { receiver(frame, host = true).receive() }
    }

    @Test
    fun commitmentAndFrameBoundsFailClosed() {
        val keys = remoteRsaKeys()
        val nonce = randomRemoteBytes()
        val secret = randomRemoteBytes()
        val encrypted = encryptRemoteSecret(keys.public.encoded, secret)
        assertContentEquals(secret, decryptRemoteSecret(keys.private, encrypted))
        val commitment = remoteHash(keys.public.encoded, nonce)
        assertFailsWith<IllegalArgumentException> { remoteTranscript(keys.public.encoded, commitment, encrypted, randomRemoteBytes(), secret) }
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(Int.MAX_VALUE) }.toByteArray()
        assertFailsWith<IllegalArgumentException> { DataInputStream(ByteArrayInputStream(bytes)).readFrame() }
    }

    @Test
    fun discoveryIncludesLimitedBroadcastWhenWifiOmitsAnInterfaceBroadcast() {
        val limited = InetAddress.getByName("255.255.255.255")
        val directed = InetAddress.getByName("192.168.1.255")
        assertEquals(listOf(limited), remoteBroadcastTargets(emptyList()))
        assertEquals(listOf(directed, limited), remoteBroadcastTargets(listOf(directed, directed)))
    }

    @Test
    fun qrAddressesPreferRealEthernetAndWifiOverVirtualVpnAndCellularAdapters() {
        val interfaces = listOf(
            lanInterface("10.7.0.2", "tun0", "WireGuard Tunnel"),
            lanInterface("192.168.56.1", "eth1", "VirtualBox Host-Only Ethernet Adapter"),
            lanInterface("172.25.10.1", "vEthernet (Default Switch)", "Hyper-V Virtual Ethernet Adapter"),
            lanInterface("192.168.1.40", "eth4", "Realtek PCIe 2.5GbE Family Controller"),
            lanInterface("192.168.1.41", "wlan0", "Intel Wi-Fi 6 AX201"),
            lanInterface("192.168.70.1", "vmnet8", "VMware Network Adapter VMnet8"),
            lanInterface("10.23.0.1", "rmnet_data0", "Mobile data"),
        )
        val ordered = prioritizedRemoteLanInterfaces(interfaces).map { it.address.hostAddress }
        assertEquals(listOf("192.168.1.40", "192.168.1.41"), ordered.take(2))
        assertEquals(interfaces.size, ordered.size, "Less useful NICs remain available as fallbacks")
        assertTrue(ordered.indexOf("192.168.56.1") > 1)
        assertTrue(ordered.indexOf("10.7.0.2") > 1)
        assertTrue(ordered.indexOf("10.23.0.1") > 1)
    }

    @Test
    fun deepScanUsesTwoDistinctPhysicalNicsBeforeAliasesOrVirtualNetworks() {
        val interfaces = listOf(
            lanInterface("172.21.0.1", "vmnet1", "VMware Virtual Network Adapter"),
            lanInterface("192.168.2.7", "wlan0", "Wi-Fi secondary address"),
            lanInterface("192.168.1.7", "wlan0", "Wi-Fi"),
            lanInterface("10.2.3.4", "eth0", "Ethernet"),
            lanInterface("10.8.0.1", "tun0", "VPN tunnel", pointToPoint = true),
        )
        val selected = remoteDeepScanInterfaces(interfaces)
        assertEquals(listOf("10.2.3.4", "192.168.1.7"), selected.map { it.address.hostAddress })
        val scanned = selected.flatMap { remoteSubnetCandidates(it.address.address, it.networkPrefixLength) }
            .map { it.hostAddress }.toSet()
        assertTrue("10.2.3.90" in scanned)
        assertTrue("192.168.1.90" in scanned)
        assertFalse("192.168.2.90" in scanned)
        assertFalse("172.21.0.90" in scanned)
    }

    @Test
    fun deepScanKeepsAddressAliasesAndVirtualOnlyFallbacksWhenNeeded() {
        val ethernetOnly = listOf(
            lanInterface("192.168.1.4", "eth0", "Ethernet"),
            lanInterface("192.168.2.4", "eth0", "Ethernet"),
        )
        assertEquals(ethernetOnly.map { it.address }, remoteDeepScanInterfaces(ethernetOnly).map { it.address })
        val withVpn = ethernetOnly + lanInterface("10.8.0.2", "tun0", "VPN", pointToPoint = true)
        assertEquals(ethernetOnly.map { it.address }, remoteDeepScanInterfaces(withVpn).map { it.address })
        val virtualOnly = listOf(
            lanInterface("10.8.0.2", "tun0", "VPN", pointToPoint = true),
            lanInterface("192.168.56.1", "vmnet1", "VMware Network Adapter"),
        )
        assertEquals(2, remoteDeepScanInterfaces(virtualOnly).size)
    }

    @Test
    fun directTcpDiscoveryOnlyRecognizesCurrentAccountAndChallenge() {
        val account = RemoteAccount("user-1", "https://one.example", "public")
        val other = account.copy(userId = "user-2")
        val nonce = "a".repeat(64)
        val probe = remoteTcpProbe(account, nonce)
        assertEquals(132, probe.size)
        assertTrue(validRemoteTcpProbe(account, probe))
        assertFalse(validRemoteTcpProbe(other, probe))
        assertFalse(validRemoteTcpProbe(account.copy(server = "https://other.example"), probe))
        assertFalse(validRemoteTcpProbe(account, probe.dropLast(1).toByteArray()))
        assertFalse(validRemoteTcpProbe(account, probe.clone().also {
            it[80] = if (it[80] == '0'.code.toByte()) '1'.code.toByte() else '0'.code.toByte()
        }))
        assertFalse(validRemoteTcpProbe(account, probe.clone().also { it[8] = '!'.code.toByte() }))
    }

    @Test
    fun subnetSweepNeverEscapesBoundedPrivateInterface() {
        val address = InetAddress.getByName("192.168.22.53")
        val candidates = remoteSubnetCandidates(address.address, 24).map { it.hostAddress }
        assertEquals(253, candidates.size)
        assertTrue("192.168.22.42" in candidates)
        assertFalse("192.168.22.53" in candidates)
        assertFalse("192.168.23.1" in candidates)
        assertEquals(1, remoteSubnetCandidates(InetAddress.getByName("10.2.0.5").address, 30).size)
        assertTrue(remoteSubnetCandidates(address.address, 15).isEmpty())
        assertTrue(remoteSubnetCandidates(address.address, 0).isEmpty())
    }

    @Test
    fun privateAddressMustMatchTheActualInterfaceSubnet() {
        fun ip(value: String) = InetAddress.getByName(value).address
        assertTrue(sameRemoteSubnet(ip("192.168.1.80"), ip("192.168.1.2"), 24))
        assertFalse(sameRemoteSubnet(ip("192.168.2.80"), ip("192.168.1.2"), 24))
        assertTrue(sameRemoteSubnet(ip("172.16.31.255"), ip("172.16.16.2"), 20))
        assertFalse(sameRemoteSubnet(ip("172.16.32.1"), ip("172.16.16.2"), 20))
        assertFalse(sameRemoteSubnet(ip("192.168.1.80"), ip("192.168.1.2"), 0))
    }

    @Test
    fun manualDiscoveryParsesOnlyBoundedNumericIpv4WithoutDns() {
        assertEquals("192.168.10.20", strictRemoteIpv4("192.168.10.20")?.hostAddress)
        assertEquals("10.1.2.3", strictRemoteIpv4(" 10.1.2.3 ")?.hostAddress)
        listOf(
            "", "localhost", "host.local", "https://192.168.1.2", "192.168.1.2:47788",
            "192.168.1", "192.168.1.2.3", "192.168.1.-2", "192.168.256.1",
            "192.168.1.%32", "192.168..1", "2001:db8::1", "８.８.８.８",
        ).forEach { value -> assertNull(strictRemoteIpv4(value), value) }
    }

    @Test
    fun rememberedPcIsOnlyAddressMetadataAndKeepsWakeConfigAfterRePairing() {
        val first = rememberRemoteDevice(emptyList(), "Family PC", "192.168.1.20", 100)
        assertEquals(1, first.size)
        assertNull(first.single().wakeMac)
        val configured = first.map { it.copy(wakeMac = "02:12:34:56:78:9A") }
        val second = rememberRemoteDevice(configured, "Renamed|PC", "192.168.1.20", 200)
        assertEquals(1, second.size)
        assertEquals("Renamed PC", second.single().name)
        assertEquals("02:12:34:56:78:9A", second.single().wakeMac)
        assertEquals(200, second.single().lastSeenEpochMs)
        assertTrue(rememberRemoteDevice(first, "bad", "attacker.example", 300) == first)
    }

    @Test
    fun bluetoothPcIsRememberedSeparatelyFromLanAndCannotBeUsedAsWakeAddress() {
        val lan = rememberRemoteDevice(emptyList(), "Home", "192.168.1.20", 100)
        val bluetooth = rememberRemoteDevice(lan, "Office|PC", "a4:b5:c6:d7:e8:f9", 200, bluetooth = true)
        assertEquals(2, bluetooth.size)
        assertEquals("A4:B5:C6:D7:E8:F9", bluetooth.first().address)
        assertTrue(bluetooth.first().bluetooth)
        assertEquals("Office PC", bluetooth.first().name)
        assertNull(bluetooth.first().wakeMac)
        assertEquals(lan.single(), bluetooth.last())
        assertEquals(bluetooth, rememberRemoteDevice(bluetooth, "invalid", "my-pc.local", 300, bluetooth = true))
        assertEquals(bluetooth, rememberRemoteDevice(bluetooth, "invalid", "01:02:03:04:05", 300, bluetooth = true))
        assertEquals(bluetooth, rememberRemoteDevice(bluetooth, "invalid", "00:00:00:00:00:00", 300, bluetooth = true))
        val updated = rememberRemoteDevice(bluetooth, "New Name", "A4:B5:C6:D7:E8:F9", 400, bluetooth = true)
        assertEquals(2, updated.size)
        assertEquals(400, updated.first().lastSeenEpochMs)
    }

    @Test
    fun bluetoothTrustRequiresBothProofsAndRemainsAccountScoped() = runBlocking {
        val store = object : RemoteBluetoothTrustStore {
            val values = mutableMapOf<String, RemoteBluetoothTrust>()
            private fun key(account: RemoteAccount, entry: String) = remoteBluetoothTrustKey(account, entry)
            override fun read(account: RemoteAccount, entry: String) = values[key(account, entry)]
            override fun write(account: RemoteAccount, entry: String, trust: RemoteBluetoothTrust) {
                values[key(account, entry)] = trust
            }
            override fun delete(account: RemoteAccount, entry: String) {
                values.remove(key(account, entry))
            }
        }
        val account = RemoteAccount("owner", "https://account.example", "public")
        val btAddress = "A4:B5:C6:D7:E8:F9"

        withPeers { host, phone ->
            assertTrue(host.supportsTrustedReconnect)
            assertTrue(phone.supportsTrustedReconnect)
            assertEquals(host.code, phone.code)
            val hostResume = async(Dispatchers.IO) { resumeBluetoothTrust(host, account, true, null, store) }
            val phoneResume = async(Dispatchers.IO) { resumeBluetoothTrust(phone, account, false, btAddress, store) }
            assertFalse(hostResume.await())
            assertFalse(phoneResume.await())
            // These calls are permitted only *after* the normal two-person,
            // same-account, server-verified authorization has succeeded.
            val hostEnroll = async(Dispatchers.IO) {
                enrollBluetoothTrust(host.channel, account, true, null, store)
            }
            val phoneEnroll = async(Dispatchers.IO) {
                enrollBluetoothTrust(phone.channel, account, false, btAddress, store)
            }
            hostEnroll.await()
            phoneEnroll.await()
        }
        val saved = requireNotNull(store.read(account, "phone:$btAddress"))
        assertEquals(saved, store.read(account, "host:${saved.id}"))
        assertNull(store.read(account.copy(userId = "another"), "phone:$btAddress"))
        assertNull(store.read(account.copy(server = "https://another.example"), "phone:$btAddress"))

        withPeers { host, phone ->
            val hostResume = async(Dispatchers.IO) { resumeBluetoothTrust(host, account, true, null, store) }
            val phoneResume = async(Dispatchers.IO) { resumeBluetoothTrust(phone, account, false, btAddress, store) }
            assertTrue(hostResume.await(), "The desktop must verify the phone's transcript-bound proof")
            assertTrue(phoneResume.await(), "The phone must verify the desktop's transcript-bound proof")
            assertNotEquals(
                remoteBluetoothTrustProof(saved, host.transcript, "phone"),
                remoteBluetoothTrustProof(saved, host.transcript, "desktop"),
            )
            assertNotEquals(
                remoteBluetoothTrustProof(saved, host.transcript, "phone"),
                remoteBluetoothTrustProof(saved, randomRemoteBytes(), "phone"),
            )
        }
        store.delete(account, "phone:$btAddress")
        withPeers { host, phone ->
            val hostResume = async(Dispatchers.IO) { resumeBluetoothTrust(host, account, true, null, store) }
            val phoneResume = async(Dispatchers.IO) { resumeBluetoothTrust(phone, account, false, btAddress, store) }
            assertFalse(hostResume.await())
            assertFalse(phoneResume.await())
        }
    }

    @Test
    fun wakePacketUsesOnlyUnicastMacAndStandard102ByteLayout() {
        assertEquals("02:12:34:56:78:9A", normalizedWakeMac("02-12-34-56-78-9a"))
        val packet = requireNotNull(wakeMagicPacket("02:12:34:56:78:9A"))
        val mac = byteArrayOf(2, 18, 52, 86, 120, -102)
        assertEquals(102, packet.size)
        assertContentEquals(ByteArray(6) { -1 }, packet.copyOfRange(0, 6))
        repeat(16) { round ->
            assertContentEquals(mac, packet.copyOfRange(6 + round * 6, 12 + round * 6))
        }
        listOf("", "AA:BB:CC", "01:02:03:04:05:06", "FF:FF:FF:FF:FF:FF",
            "00:00:00:00:00:00", "GG:01:02:03:04:05").forEach {
            assertNull(wakeMagicPacket(it), it)
        }
    }

    private suspend fun withPeers(block: suspend CoroutineScope.(RemotePairingChannel, RemotePairingChannel) -> Unit) = coroutineScope {
        bindRemoteTcpListener(0).use { listener ->
            Socket("127.0.0.1", listener.localPort).use { phone ->
                listener.accept().use { host ->
                    host.soTimeout = 5000
                    phone.soTimeout = 5000
                    val desktopHandshake = async(Dispatchers.IO) { openRemoteChannel(host, true) }
                    val phoneHandshake = async(Dispatchers.IO) { openRemoteChannel(phone, false) }
                    block(desktopHandshake.await(), phoneHandshake.await())
                }
            }
        }
    }

    private fun lanInterface(
        ip: String,
        name: String,
        displayName: String,
        pointToPoint: Boolean = false,
    ) = RemoteLanInterface(
        address = requireNotNull(strictRemoteIpv4(ip)),
        networkPrefixLength = 24,
        broadcast = null,
        name = name,
        displayName = displayName,
        pointToPoint = pointToPoint,
    )
}

package com.nuvio.app.features.remote

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Collections
import kotlinx.coroutines.*
import kotlin.test.*

/** A phone-side Bluetooth radio the test drives by hand. */
private class FakeBluetoothClient : RemoteBluetoothClient {
    override val supported = true
    var searches = 0
    var stops = 0
    var found: ((RemoteDesktop) -> Unit)? = null
    var finished: ((String?) -> Unit)? = null

    override fun discover(onFound: (RemoteDesktop) -> Unit, onFinished: (String?) -> Unit) {
        searches++
        found = onFound
        finished = onFinished
    }

    // A connection that stays in progress until the test stops the transport.
    override suspend fun connect(address: String): RemoteBluetoothConnection = awaitCancellation()

    override fun stop() { stops++ }
}

private class FakeRemoteConnectionLifetime : RemoteConnectionLifetime {
    var starts = 0
    var stops = 0
    var active = false
    override val retainWhenUiDetached: Boolean get() = active
    override fun connecting() { starts++; active = true }
    override fun disconnected() { stops++; active = false }
}

class RemoteSearchProgressTest {
    private val account = RemoteAccount("account", "https://example.org", "publishable")
    private val pc = RemoteDesktop("AA:BB:CC:DD:EE:FF", 0, "Desktop-B", bluetooth = true)

    @AfterTest
    fun reset() {
        DesktopRemote.scanning.value = false
        DesktopRemote.connecting.value = false
        DesktopRemote.connected.value = false
        DesktopRemote.activeDesktop.value = null
        DesktopRemote.pairing.value = null
        DesktopRemote.desktops.value = emptyList()
        DesktopRemote.bluetoothDesktops.value = emptyList()
        DesktopRemote.remembered.value = emptyList()
    }

    private suspend fun eventually(what: String, condition: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!condition()) delay(25) }
        } catch (_: TimeoutCancellationException) {
            fail("Timed out waiting until $what. Status: ${DesktopRemote.status.value}")
        }
    }

    @Test
    fun reattachingTheUiToAnExistingAccountDoesNotRestartItsPhoneSession() = runBlocking {
        val lifecycle = FakeRemoteConnectionLifetime()
        val phone = LanDesktopRemote(
            bluetoothClient = FakeBluetoothClient(),
            connectionLifetime = lifecycle,
            uiDispatcher = Dispatchers.Unconfined,
        )
        try {
            phone.start(account, host = false)
            phone.connect(pc)
            assertTrue(DesktopRemote.connecting.value)
            assertEquals(1, lifecycle.starts)
            assertTrue(phone.retainWhenUiDetached)
            val before = lifecycle.stops
            phone.start(account, host = false)
            assertTrue(DesktopRemote.connecting.value, "A recreated Activity must not cancel pairing")
            assertEquals(before, lifecycle.stops)
            phone.start(account.copy(userId = "another-user"), host = false)
            assertFalse(DesktopRemote.connecting.value, "A changed account must end the old connection")
            assertTrue(lifecycle.stops > before)
        } finally {
            phone.stop()
        }
    }

    @Test
    fun reconnectingToTheCurrentPcDoesNotTearDownItsSession() = runBlocking {
        val client = FakeBluetoothClient()
        val phone = LanDesktopRemote(bluetoothClient = client, uiDispatcher = Dispatchers.Unconfined)
        try {
            phone.start(account, host = false)
            DesktopRemote.activeDesktop.value = pc
            DesktopRemote.connected.value = true
            DesktopRemote.status.value = "Connected to Desktop-B"

            // The same endpoint can appear with a different display name after rediscovery.
            phone.connect(pc.copy(name = "Renamed PC"))

            assertTrue(DesktopRemote.connected.value)
            assertFalse(DesktopRemote.connecting.value)
            assertEquals(pc, DesktopRemote.activeDesktop.value)
            assertEquals("Connected to Desktop-B", DesktopRemote.status.value)
        } finally {
            phone.stop()
        }
    }

    @Test
    fun bluetoothSearchShowsProgressAndEndsWithAResult() = runBlocking {
        val client = FakeBluetoothClient()
        val phone = LanDesktopRemote(bluetoothClient = client, uiDispatcher = Dispatchers.Unconfined)
        try {
            phone.start(account, host = false)
            phone.discoverBluetooth()
            assertTrue(DesktopRemote.scanning.value, "The search must show it is running")

            // Tapping again while searching must not restart the search and empty the list.
            phone.discoverBluetooth()
            assertEquals(1, client.searches)

            // Android reports the same PC several times during one search.
            client.found!!(pc)
            client.found!!(pc.copy(name = "Desktop-B"))
            assertEquals(listOf(pc), DesktopRemote.bluetoothDesktops.value)

            client.finished!!(null)
            assertFalse(DesktopRemote.scanning.value, "The search must end")
            assertFalse(DesktopRemote.status.value.contains("Searching"), DesktopRemote.status.value)
            assertEquals(listOf(pc), DesktopRemote.bluetoothDesktops.value)
        } finally {
            phone.stop()
        }
    }

    @Test
    fun aNewBluetoothSearchDropsStalePcsImmediatelyAndIgnoresOldCallbacks() = runBlocking {
        val client = FakeBluetoothClient()
        val phone = LanDesktopRemote(bluetoothClient = client, uiDispatcher = Dispatchers.Unconfined)
        val other = RemoteDesktop("11:22:33:44:55:66", 0, "Laptop", bluetooth = true)
        try {
            phone.start(account, host = false)
            phone.discoverBluetooth()
            client.found!!(pc)
            client.finished!!(null)

            phone.discoverBluetooth()
            // A previously paired or discovered PC is not necessarily nearby.
            assertTrue(DesktopRemote.bluetoothDesktops.value.isEmpty())
            client.found!!(other)
            assertEquals(listOf(other), DesktopRemote.bluetoothDesktops.value)
            client.finished!!(null)
            assertEquals(listOf(other), DesktopRemote.bluetoothDesktops.value)
        } finally {
            phone.stop()
        }
    }

    @Test
    fun bluetoothSearchExpiresResultsWithoutDeletingSavedPcDetails() = runBlocking {
        val client = FakeBluetoothClient()
        val phone = LanDesktopRemote(
            bluetoothClient = client, uiDispatcher = Dispatchers.Unconfined, bluetoothResultsTtlMs = 40L,
        )
        try {
            phone.start(account, host = false)
            val saved = RememberedRemoteDevice("Desktop-B", pc.address, 123L, bluetooth = true)
            DesktopRemote.remembered.value = listOf(saved)
            phone.discoverBluetooth()
            client.found!!(pc)
            client.finished!!(null)
            assertEquals(listOf(pc), DesktopRemote.bluetoothDesktops.value)
            withTimeout(2_000) {
                while (DesktopRemote.bluetoothDesktops.value.isNotEmpty()) delay(10)
            }
            assertFalse(DesktopRemote.scanning.value)
            assertEquals(listOf(saved), DesktopRemote.remembered.value,
                "A live scan expiring must not remove the trusted reconnect entry")
        } finally {
            phone.stop()
        }
    }

    @Test
    fun aCancelledBluetoothScanCannotRepopulateTheNearbyList() = runBlocking {
        val client = FakeBluetoothClient()
        val phone = LanDesktopRemote(bluetoothClient = client, uiDispatcher = Dispatchers.Unconfined)
        try {
            phone.start(account, host = false)
            phone.discoverBluetooth()
            val outdatedResult = client.found!!
            phone.connect(pc)
            outdatedResult(pc)
            assertTrue(DesktopRemote.bluetoothDesktops.value.isEmpty())
        } finally {
            phone.stop()
        }
    }

    @Test
    fun bluetoothCandidatesIncludeOlderPairedPcsWithMissingDeviceClass() {
        assertTrue(isBluetoothPcCandidate("Workshop", false, false, false, true))
        assertTrue(isBluetoothPcCandidate("OfficePC", false, true, false, false))
        assertTrue(isBluetoothPcCandidate("", false, false, true, false))
        assertTrue(isBluetoothPcCandidate("Desktop-B", true, false, false, false))
        assertFalse(isBluetoothPcCandidate("Earbuds", false, false, false, false))
    }

    @Test
    fun bluetoothConnectionErrorsGiveLegacyUsersActionableAlternatives() {
        val failure = remoteBluetoothConnectionFailureMessage(
            RemoteBluetoothCompatibilityException(java.io.IOException("rfcomm unavailable")),
        )
        assertContains(failure, "RFCOMM")
        assertContains(failure, "Wi-Fi/QR")
        assertContains(remoteBluetoothConnectionFailureMessage(SecurityException()), "Nearby devices")
        assertContains(remoteBluetoothConnectionFailureMessage(IllegalArgumentException()), "Pair the PC")
    }

    @Test
    fun failedBluetoothSearchExplainsWhy() = runBlocking {
        val client = FakeBluetoothClient()
        val phone = LanDesktopRemote(bluetoothClient = client, uiDispatcher = Dispatchers.Unconfined)
        try {
            phone.start(account, host = false)
            phone.discoverBluetooth()
            client.finished!!("No Bluetooth PCs found.")
            assertFalse(DesktopRemote.scanning.value)
            assertEquals("No Bluetooth PCs found.", DesktopRemote.status.value)
        } finally {
            phone.stop()
        }
    }

    @Test
    fun connectingToABluetoothPcShowsProgressAndCannotBeInterruptedBySearching() = runBlocking {
        val client = FakeBluetoothClient()
        val phone = LanDesktopRemote(bluetoothClient = client, uiDispatcher = Dispatchers.Unconfined)
        try {
            phone.start(account, host = false)
            phone.discoverBluetooth()
            client.found!!(pc)
            val lateFinish = client.finished!!

            phone.connect(pc)
            assertTrue(DesktopRemote.connecting.value, "Connecting must be visible")
            assertFalse(DesktopRemote.scanning.value, "Connecting ends the search")
            assertContains(DesktopRemote.status.value, "Desktop-B")
            assertTrue(client.stops > 0, "The radio search must stop before connecting")

            // Android finishes the cancelled search afterwards; that must not hide the connection attempt.
            lateFinish("No Bluetooth PCs found.")
            assertContains(DesktopRemote.status.value, "Connecting")

            // Searching while a Bluetooth connection is starting would break that connection.
            phone.discoverBluetooth()
            assertEquals(1, client.searches)
        } finally {
            phone.stop()
        }
        assertFalse(DesktopRemote.connecting.value)
    }

    @Test
    fun scannedQrAddressOpensThePairingCheckWithoutAnotherTap() = runBlocking {
        // The phone only connects to private addresses on its own subnet.
        val lanAddress = privateLanAddress() ?: return@runBlocking
        val tcpPort = ServerSocket(0).use { it.localPort }
        val udpPort = DatagramSocket(0).use { it.localPort }
        val host = LanDesktopRemote(listenPort = tcpPort, discoveryPort = udpPort, uiDispatcher = Dispatchers.Default)
        val phone = LanDesktopRemote(listenPort = tcpPort, discoveryPort = udpPort, uiDispatcher = Dispatchers.Default)
        try {
            host.start(account, host = true)
            eventually("the PC listens") { DesktopRemote.hostReady.value }
            phone.start(account, host = false)

            phone.discover(lanAddress, connect = true)
            assertTrue(DesktopRemote.scanning.value, "Checking the scanned PC must be visible")
            eventually("the code check opens") { DesktopRemote.pairing.value != null }
        } finally {
            phone.stop()
            host.stop()
        }
    }

    @Test
    fun scannedQrAddressReplacesASearchThatIsAlreadyRunning() = runBlocking {
        val lanAddress = privateLanAddress() ?: return@runBlocking
        val tcpPort = ServerSocket(0).use { it.localPort }
        val udpPort = DatagramSocket(0).use { it.localPort }
        val host = LanDesktopRemote(listenPort = tcpPort, discoveryPort = udpPort, uiDispatcher = Dispatchers.Default)
        val phone = LanDesktopRemote(listenPort = tcpPort, discoveryPort = udpPort, uiDispatcher = Dispatchers.Default)
        try {
            host.start(account, host = true)
            eventually("the PC listens") { DesktopRemote.hostReady.value }
            phone.start(account, host = false)

            // Opening the controller starts a long network search; a QR result must not be dropped.
            phone.discover(deepScan = true)
            assertTrue(DesktopRemote.scanning.value)
            phone.discover(lanAddress, connect = true)
            eventually("the code check opens") { DesktopRemote.pairing.value != null }
        } finally {
            phone.stop()
            host.stop()
        }
    }

    private fun privateLanAddress(): String? = Collections.list(NetworkInterface.getNetworkInterfaces())
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.interfaceAddresses }
        .firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress }
        ?.address?.hostAddress
}

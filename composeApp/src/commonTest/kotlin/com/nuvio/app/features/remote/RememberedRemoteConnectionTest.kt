package com.nuvio.app.features.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingRemoteTransport : DesktopRemoteTransport {
    override val supported = true
    override val bluetoothSupported = true
    var scannedAddress: String? = null
    var autoConnect = false
    var connectedDesktop: RemoteDesktop? = null

    override fun start(account: RemoteAccount, host: Boolean) = Unit
    override fun stop() = Unit
    override fun discover(localIp: String?, deepScan: Boolean, connect: Boolean) {
        scannedAddress = localIp
        autoConnect = connect
    }
    override fun connect(desktop: RemoteDesktop) { connectedDesktop = desktop }
    override fun confirmPairing() = Unit
    override fun disconnect() = Unit
    override fun send(command: RemoteCommand) = Unit
}

class RememberedRemoteConnectionTest {
    @Test
    fun savedLanConnectAutomaticallyBeginsThePairingFlow() {
        val transport = RecordingRemoteTransport()
        connectRememberedRemoteDevice(transport, RememberedRemoteDevice("Office PC", "192.168.1.45", 0L))

        assertEquals("192.168.1.45", transport.scannedAddress)
        assertTrue(transport.autoConnect, "Saved-PC Connect must not stop after discovery")
        assertNull(transport.connectedDesktop)
    }

    @Test
    fun savedBluetoothConnectUsesItsOwnSecureTransport() {
        val transport = RecordingRemoteTransport()
        connectRememberedRemoteDevice(
            transport,
            RememberedRemoteDevice("Office PC", "02:12:34:56:78:9A", 0L, bluetooth = true),
        )

        assertEquals(RemoteDesktop("02:12:34:56:78:9A", 0, "Office PC", bluetooth = true), transport.connectedDesktop)
        assertNull(transport.scannedAddress)
    }

    @Test
    fun verifiedLanAndBluetoothLinksShareOnePcEntry() {
        val pcId = "0123456789abcdef0123456789abcdef"
        val lan = rememberRemoteDevice(
            emptyList(), "Workstation", "192.168.1.45", 100L, trustedId = pcId,
        )
        val both = rememberRemoteDevice(
            lan, "Workstation", "02:12:34:56:78:9A", 200L, bluetooth = true, pcId = pcId,
        )

        val groups = groupRememberedRemotePcs(both)
        assertEquals(1, groups.size)
        assertEquals("192.168.1.45", groups.single().lan?.address)
        assertEquals("02:12:34:56:78:9A", groups.single().bluetooth?.address)
        assertEquals("Workstation", groups.single().name)
    }

    @Test
    fun sameDisplayNameWithoutVerifiedIdentityNeverMerges() {
        val both = listOf(
            RememberedRemoteDevice("Workstation", "192.168.1.45", 100L),
            RememberedRemoteDevice("Workstation", "02:12:34:56:78:9A", 200L, bluetooth = true),
        )
        assertEquals(2, groupRememberedRemotePcs(both).size)
    }

    @Test
    fun updatingLanAddressRetainsBluetoothAndWakeSettings() {
        val id = "0123456789abcdef0123456789abcdef"
        val existing = listOf(
            RememberedRemoteDevice("Workstation", "192.168.1.45", 100L, wakeMac = "00:1A:2B:3C:4D:5E", trustedId = id, pcId = id),
            RememberedRemoteDevice("Workstation", "02:12:34:56:78:9A", 90L, bluetooth = true, pcId = id),
        )
        val changed = rememberRemoteDevice(existing, "Workstation", "192.168.1.81", 300L, trustedId = id, pcId = id)
        assertEquals(2, changed.size)
        assertEquals("00:1A:2B:3C:4D:5E", changed.first().wakeMac)
        assertEquals("192.168.1.81", groupRememberedRemotePcs(changed).single().lan?.address)
    }

    @Test
    fun selectedPcPrefersReachableLanThenFallsBackToTrustedBluetooth() {
        val id = "0123456789abcdef0123456789abcdef"
        val pc = groupRememberedRemotePcs(listOf(
            RememberedRemoteDevice("Workstation", "02:12:34:56:78:9A", 200L, bluetooth = true, pcId = id),
            RememberedRemoteDevice("Workstation", "192.168.1.45", 100L, trustedId = id),
        )).single()

        val wifi = RecordingRemoteTransport()
        connectRememberedRemotePc(wifi, pc, setOf("192.168.1.45"))
        assertEquals("192.168.1.45", wifi.scannedAddress)
        assertTrue(wifi.autoConnect)
        assertNull(wifi.connectedDesktop)

        val bluetooth = RecordingRemoteTransport()
        connectRememberedRemotePc(bluetooth, pc)
        assertEquals(RemoteDesktop("02:12:34:56:78:9A", 0, "Workstation", bluetooth = true), bluetooth.connectedDesktop)
        assertNull(bluetooth.scannedAddress)
    }

    @Test
    fun latestPairedPcRemainsFirstEvenWhenItHasMultipleTransports() {
        val id = "0123456789abcdef0123456789abcdef"
        val olderLan = RememberedRemoteDevice("Older", "192.168.1.45", 50L)
        val currentBluetooth = RememberedRemoteDevice(
            "Current", "02:12:34:56:78:9A", 200L, bluetooth = true, pcId = id,
        )
        val currentLan = RememberedRemoteDevice("Current", "192.168.1.46", 150L, trustedId = id)
        val pcs = groupRememberedRemotePcs(listOf(currentBluetooth, olderLan, currentLan))

        assertEquals(2, pcs.size)
        assertEquals("Current", pcs.first().name)
        assertEquals(2, pcs.first().devices.size)
        assertEquals("Older", pcs.last().name)
    }
}

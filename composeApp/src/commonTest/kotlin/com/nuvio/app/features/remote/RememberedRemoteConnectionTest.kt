package com.nuvio.app.features.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingRemoteTransport : DesktopRemoteTransport {
    override val supported = true
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
    fun automaticReconnectDoesNotPreferAnOlderBluetoothPcOverTheLatestLanPc() {
        val lan = RememberedRemoteDevice("Office PC", "192.168.1.45", 200L)
        val bluetooth = RememberedRemoteDevice("Old PC", "02:12:34:56:78:9A", 100L, bluetooth = true)

        assertNull(preferredBluetoothReconnect(listOf(lan, bluetooth), bluetoothSupported = true))
        assertEquals(bluetooth, preferredBluetoothReconnect(listOf(bluetooth, lan), bluetoothSupported = true))
        assertNull(preferredBluetoothReconnect(listOf(bluetooth, lan), bluetoothSupported = false))
    }
}

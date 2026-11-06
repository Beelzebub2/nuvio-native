package com.nuvio.app.features.remote

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import com.nuvio.app.core.ui.NuvioTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeFirewall(initial: RemoteFirewallState) : RemoteFirewallAccess {
    val flow = MutableStateFlow(initial)
    override val state: StateFlow<RemoteFirewallState> = flow
    var refreshes = 0
    var requests = 0
    var settingsOpened = 0
    override fun refresh() { refreshes++ }
    override fun requestAccess() { requests++; flow.value = RemoteFirewallState.Requesting }
    override fun openNetworkSettings() { settingsOpened++ }
}

@OptIn(ExperimentalTestApi::class)
class RemoteControllerUiTest {
    @AfterTest
    fun reset() {
        DesktopRemote.visible.value = false
        DesktopRemote.connected.value = false
        DesktopRemote.connecting.value = false
        DesktopRemote.scanning.value = false
        DesktopRemote.pairing.value = null
        DesktopRemote.hostReady.value = false
        DesktopRemote.hostFailure.value = false
        DesktopRemote.discoveryReady.value = false
        DesktopRemote.remotePlayback.value = RemotePlayback()
        DesktopRemote.desktops.value = emptyList()
        DesktopRemote.remembered.value = emptyList()
    }

    private fun ComposeUiTest.capture(name: String) {
        val file = File("build/remote-ui-capture/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }

    private fun ComposeUiTest.show(desktopLayout: Boolean, firewall: RemoteFirewallAccess = NoRemoteFirewallAccess) {
        DesktopRemote.visible.value = true
        setContent {
            NuvioTheme {
                DesktopRemoteDialogs(
                    localAddresses = { listOf("192.168.1.50") },
                    desktopLayout = desktopLayout,
                    firewall = firewall,
                    inline = true,
                )
            }
        }
    }

    @Test
    fun phoneControllerIsAFullPageWithConnectOptions() = runDesktopComposeUiTest(width = 412, height = 900) {
        DesktopRemote.status.value = "Connect to a desktop on the same Wi-Fi or Ethernet network."
        DesktopRemote.desktops.value = listOf(RemoteDesktop("192.168.1.50", REMOTE_LAN_PORT, "Living room PC"))
        DesktopRemote.remembered.value = listOf(RememberedRemoteDevice("Office PC", "192.168.1.60", 0L, "AA:BB:CC:DD:EE:FF"))
        show(desktopLayout = false)
        onNodeWithTag("pc-controller-screen").assertIsDisplayed()
        onNodeWithText("Find your Starlit PC").assertIsDisplayed()
        onNodeWithTag("desktop-remote-search-local-network").assertIsDisplayed()
        onNodeWithText("Living room PC").assertIsDisplayed()
        capture("pc-controller-find")
        onNodeWithTag("desktop-remote-manual-ip").assertDoesNotExist()
        onNodeWithTag("desktop-remote-enter-address").performClick()
        onNodeWithTag("desktop-remote-manual-ip").performScrollTo().assertIsDisplayed()
        onNodeWithText("Previously paired PCs".uppercase()).performScrollTo().assertIsDisplayed()
        onNodeWithText("Can't find your PC?").performScrollTo().assertIsDisplayed()
        capture("pc-controller-find-bottom")
    }

    @Test
    fun phoneControllerShowsConnectionProgressAndHoldsOtherOptions() = runDesktopComposeUiTest(width = 412, height = 900) {
        DesktopRemote.connecting.value = true
        DesktopRemote.status.value = "Connecting to Living room PC…"
        DesktopRemote.desktops.value = listOf(RemoteDesktop("192.168.1.50", REMOTE_LAN_PORT, "Living room PC"))
        show(desktopLayout = false)
        onNodeWithText("Connecting to your PC").assertIsDisplayed()
        onNodeWithText("Connecting").assertIsDisplayed()
        onNodeWithText("Connecting to Living room PC…").assertIsDisplayed()
        onNodeWithTag("desktop-remote-search-local-network").assertIsNotEnabled()
        capture("pc-controller-connecting")

        // Searching shows its own progress, and the result replaces it.
        runOnIdle {
            DesktopRemote.connecting.value = false
            DesktopRemote.scanning.value = true
            DesktopRemote.status.value = "Searching nearby Bluetooth PCs…"
        }
        onNodeWithText("Looking for your PC").assertIsDisplayed()
        onNodeWithText("Searching").assertIsDisplayed()
        runOnIdle {
            DesktopRemote.scanning.value = false
            DesktopRemote.status.value = "Choose your PC."
        }
        onNodeWithText("Find your Starlit PC").assertIsDisplayed()
        onNodeWithText("Choose your PC.").assertIsDisplayed()
        onNodeWithTag("desktop-remote-search-local-network").assertIsEnabled()
    }

    @Test
    fun connectedControllerSplitsRemoteDownloadsAndPcsIntoTabs() = runDesktopComposeUiTest(width = 412, height = 900) {
        DesktopRemote.connected.value = true
        DesktopRemote.status.value = "Connected to Living room PC"
        DesktopRemote.remotePlayback.value = RemotePlayback(
            playbackId = "playback-1",
            title = "Severance · S2 E3 · Who Is Alive?",
            playing = true,
            position = 1_234_000,
            duration = 3_120_000,
            volume = 0.62f,
            skip = true,
            episodes = listOf(RemoteChoice("e3", "Episode 3", selected = true), RemoteChoice("e4", "Episode 4")),
            subtitles = listOf(RemoteChoice("s1", "English", selected = true)),
        )
        show(desktopLayout = false)
        onNodeWithText("Now playing on your PC".uppercase()).assertIsDisplayed()
        onNodeWithText("Severance · S2 E3 · Who Is Alive?").assertIsDisplayed()
        onNodeWithContentDescription("Pause").assertIsDisplayed()
        capture("pc-controller-remote")
        onNodeWithText("Downloads").performClick()
        onNodeWithText("Open the same profile on your PC and phone to manage its downloads.").assertIsDisplayed()
        onNodeWithText("PCs").performClick()
        onNodeWithText("Disconnect").assertIsDisplayed()
        capture("pc-controller-pcs-connected")
    }

    @Test
    fun desktopWindowOffersNetworkHelpOnlyWhenRequested() = runDesktopComposeUiTest(width = 900, height = 680) {
        DesktopRemote.hostReady.value = true
        DesktopRemote.discoveryReady.value = true
        DesktopRemote.status.value = "Ready to pair with your phone on this local network."
        val firewall = FakeFirewall(RemoteFirewallState.Blocked)
        show(desktopLayout = true, firewall = firewall)
        onNodeWithTag("desktop-pairing-qr").assertIsDisplayed()
        onNodeWithTag("remote-firewall-card").assertDoesNotExist()
        runOnIdle { assertEquals(0, firewall.refreshes) }
        onNodeWithTag("remote-network-help").performScrollTo().performClick()
        onNodeWithText("Connection permission isn't confirmed").performScrollTo().assertIsDisplayed()
        runOnIdle { assertEquals(1, firewall.refreshes) }
        onNodeWithTag("remote-firewall-allow").performScrollTo()
        capture("phone-remote-network-help")
        onNodeWithTag("remote-firewall-allow").performScrollTo().performClick()
        runOnIdle { assertEquals(1, firewall.requests) }
        onNodeWithText("Approve the Windows prompt to let Starlit through the firewall…").performScrollTo().assertIsDisplayed()
        runOnIdle { firewall.flow.value = RemoteFirewallState.PublicNetwork }
        onNodeWithText("Open network settings").performScrollTo().performClick()
        runOnIdle { assertEquals(1, firewall.settingsOpened) }
        runOnIdle { firewall.flow.value = RemoteFirewallState.Unverified }
        onNodeWithText("Couldn't check Windows Firewall").performScrollTo().assertIsDisplayed()
        runOnIdle { firewall.flow.value = RemoteFirewallState.Allowed }
        onNodeWithText("Windows rules allow local connections and automatic discovery. Your phone still needs a working network route to this PC.")
            .performScrollTo().assertIsDisplayed()
        capture("phone-remote-firewall-allowed")
        runOnIdle { firewall.flow.value = RemoteFirewallState.NotNeeded }
        onNodeWithTag("remote-firewall-card").assertDoesNotExist()
    }

    @Test
    fun connectedPhoneHidesIrrelevantLanDiagnostics() = runDesktopComposeUiTest(width = 900, height = 680) {
        DesktopRemote.hostReady.value = true
        val firewall = FakeFirewall(RemoteFirewallState.Blocked)
        show(desktopLayout = true, firewall = firewall)
        onNodeWithTag("remote-network-help").performScrollTo().performClick()
        runOnIdle { DesktopRemote.connected.value = true }
        for (state in listOf(RemoteFirewallState.Blocked, RemoteFirewallState.PublicNetwork,
            RemoteFirewallState.Unverified, RemoteFirewallState.DiscoveryRestricted)) {
            runOnIdle { firewall.flow.value = state }
            onNodeWithTag("remote-firewall-card").assertDoesNotExist()
            onNodeWithTag("remote-network-help").assertDoesNotExist()
        }
        onNodeWithText("A phone is connected to this desktop.").performScrollTo().assertIsDisplayed()
        capture("phone-remote-connected-no-warning")
    }

    @Test
    fun udpRuleWarningKeepsQrPairingAvailable() = runDesktopComposeUiTest(width = 900, height = 760) {
        DesktopRemote.hostReady.value = true
        DesktopRemote.discoveryReady.value = true
        show(desktopLayout = true, firewall = FakeFirewall(RemoteFirewallState.DiscoveryRestricted))
        onNodeWithTag("desktop-pairing-qr").assertIsDisplayed()
        onNodeWithTag("remote-network-help").performScrollTo().performClick()
        onNodeWithText("Try QR or an IP address").performScrollTo().assertIsDisplayed()
        onNodeWithText("Connection permission isn't confirmed").assertDoesNotExist()
        onNodeWithText("Allow through firewall").performScrollTo()
        capture("phone-remote-discovery-restricted")
    }

    @Test
    fun idleDesktopNeverShowsAStartupFirewallPrompt() = runDesktopComposeUiTest(width = 900, height = 680) {
        DesktopRemote.hostReady.value = true
        DesktopRemote.visible.value = false
        val firewall = FakeFirewall(RemoteFirewallState.PublicNetwork)
        setContent { NuvioTheme { DesktopRemoteDialogs(desktopLayout = true, firewall = firewall, inline = true) } }
        mainClock.advanceTimeBy(5_000)
        onNodeWithText("Let your phone find this PC?").assertDoesNotExist()
        onNodeWithTag("remote-firewall-card").assertDoesNotExist()
        runOnIdle {
            assertEquals(0, firewall.refreshes)
            assertEquals(0, firewall.requests)
        }
    }

    @Test
    fun desktopExplainsOccupiedPortInsteadOfShowingAnIndefiniteSpinner() = runDesktopComposeUiTest(width = 900, height = 680) {
        DesktopRemote.hostReady.value = false
        DesktopRemote.hostFailure.value = true
        DesktopRemote.status.value = remoteHostFailureMessage(java.net.BindException("Address already in use"))
        show(desktopLayout = true, firewall = FakeFirewall(RemoteFirewallState.Unverified))
        onNodeWithText("Not ready").assertIsDisplayed()
        onNodeWithTag("remote-network-help").assertDoesNotExist()
        onNodeWithTag("remote-firewall-card").assertDoesNotExist()
        onNodeWithText(DesktopRemote.status.value).assertIsDisplayed()
    }

    @Test
    fun desktopWindowShowsThePairingCodeInPlace() = runDesktopComposeUiTest(width = 900, height = 680) {
        DesktopRemote.hostReady.value = true
        DesktopRemote.pairing.value = RemotePairing("482913", "Pixel 9")
        show(desktopLayout = true)
        onNodeWithTag("remote-pairing-code").assertIsDisplayed()
        onNodeWithText("Codes match").assertIsDisplayed()
        onNodeWithTag("desktop-pairing-qr").assertDoesNotExist()
        capture("phone-remote-pairing")
    }

    @Test
    fun narrowDesktopWindowStacksTheQrAboveTheDetails() = runDesktopComposeUiTest(width = 580, height = 900) {
        DesktopRemote.hostReady.value = true
        DesktopRemote.connected.value = true
        show(desktopLayout = true, firewall = FakeFirewall(RemoteFirewallState.Allowed))
        onNodeWithTag("desktop-pairing-qr").assertIsDisplayed()
        onNodeWithText("A phone is connected to this desktop.").performScrollTo().assertIsDisplayed()
        onNodeWithText("Disconnect").assertIsDisplayed()
        capture("phone-remote-narrow-connected")
    }
}
